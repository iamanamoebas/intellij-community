/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.codeInsight.intentions;

import com.google.common.base.Function;
import com.intellij.codeInsight.CodeInsightUtilCore;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.template.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.documentation.doctest.PyDocstringFile;
import com.jetbrains.python.psi.*;
import org.jetbrains.annotations.NotNull;

import static com.jetbrains.python.codeInsight.intentions.SpecifyTypeInPy3AnnotationsIntention.annotateParameter;
import static com.jetbrains.python.codeInsight.intentions.SpecifyTypeInPy3AnnotationsIntention.annotateReturnType;
import static com.jetbrains.python.codeInsight.intentions.TypeIntention.getCallable;
import static com.jetbrains.python.codeInsight.intentions.TypeIntention.resolvesToFunction;

/**
 * @author traff
 */
public class PyAnnotateTypesIntention implements IntentionAction {
  private String myText = PyBundle.message("INTN.annotate.types");

  @NotNull
  public String getText() {
    return myText;
  }

  @NotNull
  public String getFamilyName() {
    return PyBundle.message("INTN.annotate.types");
  }

  public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
    if (!(file instanceof PyFile) || file instanceof PyDocstringFile) return false;

    if (!LanguageLevel.forElement(file).isPy3K()) return false;

    updateText();

    final PsiElement elementAt = PyUtil.findNonWhitespaceAtOffset(file, editor.getCaretModel().getOffset());
    if (elementAt == null) return false;

    if (resolvesToFunction(elementAt, new Function<PyFunction, Boolean>() {
      @Override
      public Boolean apply(PyFunction input) {
        return true;
      }
    })) {
      updateText();
      return true;
    }
    return false;
  }

  public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
    final PsiElement elementAt = PyUtil.findNonWhitespaceAtOffset(file, editor.getCaretModel().getOffset());
    final PyCallable callable = getCallable(elementAt);


    final TemplateBuilder builder = TemplateBuilderFactory.getInstance().createTemplateBuilder(callable);

    PyExpression returnType = annotateReturnType(project, editor.getDocument(), elementAt, false);

    if (returnType != null) {
      builder.replaceElement(returnType, returnType.getText());
    }

    if (callable instanceof PyFunction) {
      PyFunction function = (PyFunction) callable;
      PyParameter[] params = function.getParameterList().getParameters();

      for (int i = params.length - 1; i >= 0; i--) {
        if (params[i] instanceof PyNamedParameter) {
          params[i] = annotateParameter(project, editor, (PyNamedParameter)params[i], false);
        }
      }


      for (int i = params.length - 1; i >= 0; i--) {
        if (params[i] instanceof PyNamedParameter) {
          params[i] = CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(params[i]);
          PyAnnotation annotation = ((PyNamedParameter)params[i]).getAnnotation();
          if (annotation != null) {
            PyExpression annotationValue = annotation.getValue();
            if (annotationValue != null) {
              builder.replaceElement(annotationValue, annotationValue.getText());
            }
          }
        }
      }
    }
    if (callable != null) {
      final Template template = ((TemplateBuilderImpl)builder).buildInlineTemplate();

      int offset = callable.getTextRange().getStartOffset();

      final OpenFileDescriptor descriptor = new OpenFileDescriptor(
        project,
        callable.getContainingFile().getVirtualFile(),
        offset
      );
      final Editor targetEditor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
      if (targetEditor != null) {
        targetEditor.getCaretModel().moveToOffset(offset);
        TemplateManager.getInstance(project).startTemplate(targetEditor, template);
      }
    }
  }

  @Override
  public boolean startInWriteAction() {
    return true;
  }


  protected void updateText() {
    myText = PyBundle.message("INTN.annotate.types");
  }
}