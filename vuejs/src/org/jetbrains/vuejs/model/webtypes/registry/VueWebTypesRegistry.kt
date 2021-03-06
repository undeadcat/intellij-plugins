// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.vuejs.model.webtypes.registry

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.intellij.javaee.ExternalResourceManager
import com.intellij.javascript.nodejs.PackageJsonData
import com.intellij.javascript.nodejs.packageJson.NodePackageBasicInfo
import com.intellij.javascript.nodejs.packageJson.NpmRegistryService
import com.intellij.lang.javascript.buildTools.npm.PackageJsonUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValueProvider.Result
import com.intellij.util.io.HttpRequests
import com.intellij.util.text.SemVer
import one.util.streamex.EntryStream
import one.util.streamex.StreamEx
import org.jdom.Element
import org.jetbrains.vuejs.model.VueGlobal
import org.jetbrains.vuejs.model.VuePlugin
import org.jetbrains.vuejs.model.webtypes.VueWebTypesGlobal
import org.jetbrains.vuejs.model.webtypes.VueWebTypesPlugin
import org.jetbrains.vuejs.model.webtypes.json.WebTypes
import java.io.IOException
import java.util.*
import java.util.Collections.emptySortedMap
import java.util.concurrent.*
import kotlin.collections.HashMap
import kotlin.math.max

@State(name = "VueWebTypesRegistry", storages = [Storage("web-types-registry.xml")])
class VueWebTypesRegistry : PersistentStateComponent<Element> {

  companion object {
    private val LOG = Logger.getInstance(VueWebTypesRegistry::class.java)
    private const val WEB_TYPES_ENABLED_PACKAGES_URL = "https://raw.githubusercontent.com/JetBrains/web-types/master/packages/registry.json"

    const val PACKAGE_PREFIX = "@web-types"

    internal val STATE_UPDATE_INTERVAL = TimeUnit.MINUTES.toNanos(10)

    internal val CHECK_TIMEOUT = TimeUnit.MILLISECONDS.toNanos(1)

    internal val EDT_TIMEOUT = TimeUnit.MILLISECONDS.toNanos(5)
    internal val EDT_RETRY_INTERVAL = TimeUnit.SECONDS.toNanos(1)

    internal val NON_EDT_TIMEOUT = TimeUnit.SECONDS.toNanos(10)
    internal val NON_EDT_RETRY_INTERVAL = TimeUnit.MINUTES.toNanos(1)

    val MODIFICATION_TRACKER = ModificationTracker { instance.myStateTimestamp }

    val instance: VueWebTypesRegistry get() = ServiceManager.getService(VueWebTypesRegistry::class.java)

    fun createWebTypesGlobal(project: Project, packageJsonFile: VirtualFile, owner: VueGlobal): Result<VueGlobal>? =
      loadWebTypes(packageJsonFile)?.let { (webTypes, file) ->
        Result.create(VueWebTypesGlobal(project, packageJsonFile, webTypes, owner), packageJsonFile, file)
      }

    fun createWebTypesPlugin(project: Project, packageJsonFile: VirtualFile, owner: VuePlugin): Result<VuePlugin>? {
      val data = PackageJsonUtil.getOrCreateData(packageJsonFile)
      loadWebTypes(packageJsonFile, data)?.let { (webTypes, file) ->
        return Result.create(VueWebTypesPlugin(project, packageJsonFile, webTypes, owner),
                             packageJsonFile, file, MODIFICATION_TRACKER)
      }
      return instance.getWebTypesPlugin(project, packageJsonFile, data, owner)
    }

    private fun loadWebTypes(packageJsonFile: VirtualFile,
                             packageJson: PackageJsonData = PackageJsonUtil.getOrCreateData(packageJsonFile))
      : Pair<WebTypes, VirtualFile>? {
      val webTypesFile = packageJson.webTypes?.let {
        packageJsonFile.parent?.findFileByRelativePath(it)
      }
      return webTypesFile?.inputStream?.let {
        Pair(createObjectMapper().readValue(it, WebTypes::class.java), webTypesFile)
      }
    }

    private fun createObjectMapper() = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
  }

  private var myStateLock = Object()
  private var myState = State(emptySortedMap(), emptySet())
  @Volatile
  private var myStateVersion = 0
  private var myStateTimestamp = 0L
  private var myStateUpdate: FutureResultProvider<Boolean>? = null

  private var myPluginLoadMap = HashMap<String, FutureResultProvider<WebTypes>>()
  private var myPluginCache = CacheBuilder.newBuilder()
    .maximumSize(20)
    .expireAfterAccess(30, TimeUnit.MINUTES)
    .build(CacheLoader.from(this::buildPackageWebTypes))

  override fun getState(): Element {
    synchronized(myState) {
      return myState.toElement()
    }
  }

  override fun loadState(stateElement: Element) {
    synchronized(myStateLock) {
      myState = State(stateElement)
      incStateVersion()
    }
  }

  val webTypesEnabledPackages: Result<Set<String>>
    get() = processState { state, tracker ->
      Result.create(state.enabledPackages, tracker)
    }

  private fun getWebTypesPlugin(project: Project,
                                packageJsonFile: VirtualFile,
                                packageJson: PackageJsonData,
                                owner: VuePlugin): Result<VuePlugin>? {
    return processState { state, tracker ->
      val webTypesPackageName = (packageJson.name ?: return@processState null)
        .replace(Regex("^@(.*)/(.*)$"), "at-$1-$2")
      val versions = state.availableVersions["@web-types/$webTypesPackageName"]
      if (versions == null || versions.isEmpty()) return@processState null

      val pkgVersion = packageJson.version

      val webTypesVersionEntry = versions.entries.find {
        pkgVersion == null || it.key <= pkgVersion
      } ?: return@processState null

      return@processState loadPackageWebTypes(webTypesVersionEntry.value)
        ?.let { VueWebTypesPlugin(project, packageJsonFile, it, owner) }
        ?.let { Result.create(it, tracker, packageJsonFile) }
    }
  }

  private fun loadPackageWebTypes(tarballUrl: String): WebTypes? {
    synchronized(myPluginLoadMap) {
      myPluginCache.getIfPresent(tarballUrl)?.let { return it }
      myPluginLoadMap.computeIfAbsent(tarballUrl) {
        FutureResultProvider(Callable {
          myPluginCache.get(tarballUrl)
        })
      }
    }.result
      ?.let {
        synchronized(myPluginLoadMap) {
          myPluginLoadMap.remove(tarballUrl)
        }
        return it
      }
    ?: return null
  }

  private fun buildPackageWebTypes(tarballUrl: String?): WebTypes? {
    tarballUrl ?: return null
    val webTypesJson = createObjectMapper().readValue(VueWebTypesJsonsCache.getWebTypesJson(tarballUrl), WebTypes::class.java)
    incStateVersion()
    return webTypesJson
  }

  private fun <T> processState(processor: (State, ModificationTracker) -> T): T {
    val state: State
    val tracker: ModificationTracker
    updateStateIfNeeded()
    synchronized(myStateLock) {
      state = myState
      tracker = StateModificationTracker(myStateVersion)
    }
    return processor(state, tracker)
  }

  private fun updateStateIfNeeded() {
    val stateUpdate: FutureResultProvider<Boolean>
    synchronized(myStateLock) {
      if (myStateTimestamp + STATE_UPDATE_INTERVAL >= System.nanoTime()
          // Use #loadState() in test mode
          || ApplicationManager.getApplication().isUnitTestMode) {
        return
      }
      if (myStateUpdate == null) {
        myStateUpdate = FutureResultProvider(Callable {
          val state = createNewState()
          synchronized(myStateLock) {
            myState = state
            incStateVersion()
            myStateTimestamp = System.nanoTime()
          }
          true
        })
      }
      stateUpdate = myStateUpdate!!
    }

    stateUpdate.result?.let {
      synchronized(myStateLock) {
        if (myStateUpdate == stateUpdate) {
          myStateUpdate = null
        }
      }
    }
  }

  private fun createNewState(): State {
    val enabledPackagesFuture = ApplicationManager.getApplication().executeOnPooledThread(Callable {
      try {
        HttpRequests.request(WEB_TYPES_ENABLED_PACKAGES_URL)
          .productNameAsUserAgent()
          .gzip(true)
          .readString(null)
      }
      catch (e: Exception) {
        e
      }
    })
    val packageInfo: MutableList<NodePackageBasicInfo> = mutableListOf()
    NpmRegistryService.getInstance().findPackages(null,
                                                  NpmRegistryService.fullTextSearch(PACKAGE_PREFIX),
                                                  50,
                                                  { it.name.startsWith("$PACKAGE_PREFIX/") },
                                                  { packageInfo.add(it) })

    val availableVersions: SortedMap<String, SortedMap<SemVer, String>> = StreamEx.of(packageInfo)
      .parallel()
      .map { it.name }
      .mapToEntry {
        NpmRegistryService.getInstance()
          .getCachedOrFetchPackageVersionsFuture(it)
          .get()
      }
      .nonNullValues()
      .mapValues {
        EntryStream.of(it!!.versionsInfo)
          .filter { (_, info) -> !info.isDeprecated && info.url != null }
          .mapValues { info -> info.url }
          .into(TreeMap<SemVer, String>(Comparator.reverseOrder()) as SortedMap<SemVer, String>)
      }
      .toSortedMap()
    val enabledPackages = enabledPackagesFuture.get()
      ?.let {
        when (it) {
          is Exception -> throw it
          is String -> ObjectMapper().readTree(it) as? ObjectNode
          else -> null
        }
      }
      ?.get("vue")
      ?.let { it as? ArrayNode }
      ?.elements()
      ?.asSequence()
      ?.mapNotNull { (it as? TextNode)?.asText() }
      ?.toSet()
    return State(availableVersions, enabledPackages ?: emptySet())
  }

  private fun incStateVersion() {
    synchronized(myStateLock) {
      myStateVersion++
      // Inform that external resource has changed to reload XmlElement/AttributeDescriptors
      ExternalResourceManager.getInstance().incModificationCount()
    }
  }

  private class State {

    val availableVersions: SortedMap<String, SortedMap<SemVer, String>>
    val enabledPackages: Set<String>

    constructor(availableVersions: SortedMap<String, SortedMap<SemVer, String>>, enabledPackages: Set<String>) {
      this.availableVersions = availableVersions
      this.enabledPackages = enabledPackages
    }

    constructor(root: Element) {
      availableVersions = TreeMap()
      enabledPackages = mutableSetOf()

      for (versions in root.getChildren(PACKAGE_ELEMENT)) {
        val name = versions.getAttributeValue(NAME_ATTR) ?: continue
        val map = availableVersions.computeIfAbsent(name) { TreeMap(Comparator.reverseOrder<SemVer>()) }
        for (version in versions.getChildren(VERSION_ELEMENT)) {
          val ver = version.getAttributeValue(VALUE_ATTR)?.let { SemVer.parseFromText(it) } ?: continue
          val url = version.getAttributeValue(URL_ATTR) ?: continue
          map[ver] = url
        }
      }
      for (enabled in root.getChild("enabled")?.getChildren(PACKAGE_ELEMENT) ?: emptyList()) {
        enabled.getAttributeValue(NAME_ATTR)?.let { enabledPackages.add(it) }
      }
    }

    fun toElement(): Element {
      val root = Element(WEB_TYPES_ELEMENT)
      for (entry in availableVersions) {
        val versionsEl = Element(PACKAGE_ELEMENT)
        versionsEl.setAttribute(NAME_ATTR, entry.key)
        for (versionEntry in entry.value) {
          val versionEl = Element(VERSION_ELEMENT)
          versionEl.setAttribute(VALUE_ATTR, versionEntry.key.rawVersion)
          versionEl.setAttribute(URL_ATTR, versionEntry.value)
          versionsEl.addContent(versionEl)
        }
        root.addContent(versionsEl)
      }
      return root
    }

    companion object {
      const val WEB_TYPES_ELEMENT = "web-types"
      const val PACKAGE_ELEMENT = "package"
      const val VERSION_ELEMENT = "version"

      const val NAME_ATTR = "name"
      const val VALUE_ATTR = "value"
      const val URL_ATTR = "url"
    }
  }

  private inner class StateModificationTracker(private val stateVersion: Int) : ModificationTracker {
    override fun getModificationCount(): Long {
      return if (stateVersion != myStateVersion) -1 else 0
    }
  }

  private class FutureResultProvider<T>(private val operation: Callable<T>) {
    private val myLock = Object()
    private var myFuture: Future<*>? = null
    private var myRetryTime = 0L

    val result: T?
      get() {
        val future: Future<*>
        synchronized(myLock) {
          if (myRetryTime > System.nanoTime()) {
            return null
          }
          if (myFuture == null) {
            myFuture = ApplicationManager.getApplication().executeOnPooledThread(Callable {
              try {
                operation.call()
              }
              catch (e: Exception) {
                // we need to catch the exception and process on the main thread
                e
              }
            })
          }
          future = myFuture!!
        }
        val app = ApplicationManager.getApplication()
        val edt = app.isDispatchThread && !app.isUnitTestMode
        var timeout = if (edt) EDT_TIMEOUT else NON_EDT_TIMEOUT
        try {
          do {
            ProgressManager.checkCanceled()
            try {
              val result = future.get(CHECK_TIMEOUT, TimeUnit.NANOSECONDS)
              if (result is ProcessCanceledException) {
                // retry at the next occasion without waiting
                synchronized(myLock) {
                  myFuture = null
                }
                return null
              }
              else if (result is Exception) {
                // wrap it and pass to the exception catch block below
                throw ExecutionException(result)
              }
              // future returns either T or Exception, so result must be T at this point
              @Suppress("UNCHECKED_CAST")
              return result as T
            }
            catch (e: TimeoutException) {
              timeout -= CHECK_TIMEOUT
            }
          }
          while (timeout > 0)
          synchronized(myLock) {
            myRetryTime = max(myRetryTime, System.nanoTime() + (if (edt) EDT_RETRY_INTERVAL else NON_EDT_RETRY_INTERVAL))
          }
        }
        catch (e: ExecutionException) {
          // Do not log IOExceptions as errors, since they can appear because of HTTP communication
          if (e.cause is IOException) {
            LOG.warn(e)
          }
          else {
            LOG.error(e)
          }
          synchronized(myLock) {
            myRetryTime = max(myRetryTime, System.nanoTime() + NON_EDT_RETRY_INTERVAL)
            myFuture = null
          }
        }
        return null
      }
  }
}
