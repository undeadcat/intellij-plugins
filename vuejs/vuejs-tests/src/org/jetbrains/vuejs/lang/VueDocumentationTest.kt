package org.jetbrains.vuejs.lang

import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import junit.framework.TestCase

class VueDocumentationTest : BasePlatformTestCase() {
  override fun getTestDataPath(): String = PathManager.getHomePath() + "/contrib/vuejs/vuejs-tests/testData/"

  fun testDocumentationFromDefinitions() {
    createPackageJsonWithVueDependency(myFixture, "")
    myFixture.copyDirectoryToProject("./types/node_modules", "./node_modules")
    myFixture.configureByText("testDocumentationFromDefinitions.vue", """
<script>
  export default {
    <caret>mixins: []
  }
</script>
""")
    val elementAtCaret = myFixture.elementAtCaret
    val documentationProvider = DocumentationManager.getProviderFromElement(elementAtCaret)
    val inlineDoc = documentationProvider.generateDoc(elementAtCaret, elementAtCaret)

    TestCase.assertNotNull(inlineDoc)
    TestCase.assertTrue(inlineDoc!!.trim().contains("Fictive mixins comment"))
  }
}
