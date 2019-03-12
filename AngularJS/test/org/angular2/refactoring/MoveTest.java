// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.angular2.refactoring;

import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor;
import com.intellij.util.containers.ContainerUtil;
import org.angular2.Angular2MultiFileFixtureTestCase;
import org.angularjs.AngularTestUtil;
import org.jetbrains.annotations.NotNull;

public class MoveTest extends Angular2MultiFileFixtureTestCase {

  @Override
  protected String getTestDataPath() {
    return AngularTestUtil.getBaseTestDataPath(getClass()) + "move";
  }

  @NotNull
  @Override
  protected String getTestRoot() {
    return "/";
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      Messages.setTestDialog(TestDialog.DEFAULT);
    }
    catch (Throwable e) {
      addSuppressedException(e);
    }
    finally {
      super.tearDown();
    }
  }

  public void testSimpleRelative() {
    doMultiFileTest("dest", "component.ts");
  }

  public void testRelative() {
    doMultiFileTest("src/app/dest",
                    "src/app/app.component.ts",
                    "src/app/dest/test2/app2.component.ts",
                    "src/app/test1/app1.component.ts");
  }

  public void testAmbiguousBaseRelative() {
    doMultiFileTest("src/app/dest",
                    "src/app/app.component.ts",
                    "src/app/dest/test2/app2.component.ts",
                    "src/app/test1/app1.component.ts");
  }

  public void testAmbiguousFileRelative() {
    doMultiFileTest("src/app/dest",
                    "src/app/app.component.ts",
                    "src/app/dest/test2/app2.component.ts",
                    "src/app/test1/app1.component.ts");
  }

  public void testBatchRelative() {
    doMultiFileTest("src/app/dest",
                    "src/app/app.component.ts",
                    "src/app/app.component.html",
                    "src/app/dest/test2/app2.component.ts",
                    "src/app/dest/test2/app2.component.html",
                    "src/app/test1/app1.component.ts",
                    "src/app/test1/app1.component.html");
  }

  public void testBatchAmbiguousBaseRelative() {
    doMultiFileTest("src/app/dest",
                    "src/app/app.component.ts",
                    "src/app/app.component.html",
                    "src/app/dest/test2/app2.component.ts",
                    "src/app/dest/test2/app2.component.html",
                    "src/app/test1/app1.component.ts",
                    "src/app/test1/app1.component.html");
  }

  public void testBatchAmbiguousFileRelative() {
    doMultiFileTest("src/app/dest",
                    "src/app/app.component.ts",
                    "src/app/app.component.html",
                    "src/app/dest/test2/app2.component.ts",
                    "src/app/dest/test2/app2.component.html",
                    "src/app/test1/app1.component.ts",
                    "src/app/test1/app1.component.html");
  }

  private void doMultiFileTest(String destinationDir, String... files) {
    doTest((rootDir, rootAfter) -> {
      MoveFilesOrDirectoriesProcessor moveProcessor = new MoveFilesOrDirectoriesProcessor(
        getProject(), ContainerUtil.map2Array(files, PsiElement.class, this::map2FileOrDir),
        myFixture.getPsiManager().findDirectory(myFixture.getTempDirFixture().findOrCreateDir(destinationDir)),
        true, false, false,
        null, null);
      moveProcessor.run();
    });
  }

  @NotNull
  private PsiElement map2FileOrDir(String name) {
    VirtualFile vf = myFixture.getTempDirFixture().getFile(name);
    assert vf != null : "Can't find " + name;
    PsiFile psiFile = myFixture.getPsiManager().findFile(vf);
    if (psiFile != null) {
      return psiFile;
    }
    PsiDirectory psiDirectory = myFixture.getPsiManager().findDirectory(vf);
    assert psiDirectory != null : "Can't find PsiDir or PsiFile for " + name;
    return psiDirectory;
  }
}