// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.vuejs.lang.html.parser

import com.intellij.lang.PsiBuilder
import com.intellij.lang.html.HTMLParser
import com.intellij.lang.html.HTMLParserDefinition
import com.intellij.lang.javascript.dialects.JSLanguageLevel
import com.intellij.lang.javascript.settings.JSRootConfiguration
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.html.HtmlFileImpl
import com.intellij.psi.tree.IFileElementType
import org.jetbrains.vuejs.lang.html.lexer.VueLexer

class VueParserDefinition : HTMLParserDefinition() {
  override fun createLexer(project: Project): Lexer {
    val level = JSRootConfiguration.getInstance(project).languageLevel
    return VueLexer(if (level.isES6Compatible) level else JSLanguageLevel.ES6)
  }

  override fun getFileNodeType(): IFileElementType {
    return VueFileElementType.INSTANCE
  }

  override fun createFile(viewProvider: FileViewProvider): PsiFile {
    return HtmlFileImpl(viewProvider, VueFileElementType.INSTANCE)
  }

  override fun createParser(project: Project?): HTMLParser = object : HTMLParser() {
    override fun createHtmlParsing(builder: PsiBuilder): VueParsing = VueParsing(builder)
  }
}
