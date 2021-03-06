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
package com.intellij.xdebugger.impl.ui.tree.nodes;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.FontPreferences;
import com.intellij.openapi.editor.impl.ComplementaryFontsRegistry;
import com.intellij.openapi.editor.impl.FontInfo;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTextContainer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ThreeState;
import com.intellij.util.ui.UIUtil;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.*;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import com.intellij.xdebugger.impl.frame.XDebugView;
import com.intellij.xdebugger.impl.frame.XValueMarkers;
import com.intellij.xdebugger.impl.frame.XValueWithInlinePresentation;
import com.intellij.xdebugger.impl.frame.XVariablesView;
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil;
import com.intellij.xdebugger.impl.ui.XDebuggerUIConstants;
import com.intellij.xdebugger.impl.ui.tree.ValueMarkup;
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree;
import com.intellij.xdebugger.settings.XDebuggerSettingsManager;
import com.intellij.xdebugger.ui.DebuggerColors;
import gnu.trove.TObjectLongHashMap;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.Comparator;
import java.util.List;

/**
 * @author nik
 */
public class XValueNodeImpl extends XValueContainerNode<XValue> implements XValueNode, XCompositeNode, XValueNodePresentationConfigurator.ConfigurableXValueNode, RestorableStateNode {
  public static final Comparator<XValueNodeImpl> COMPARATOR = (o1, o2) -> StringUtil.naturalCompare(o1.getName(), o2.getName());

  private static final int MAX_NAME_LENGTH = 100;

  private final String myName;
  @Nullable
  private String myRawValue;
  private XFullValueEvaluator myFullValueEvaluator;
  private boolean myChanged;
  private XValuePresentation myValuePresentation;

  //todo[nik] annotate 'name' with @NotNull
  public XValueNodeImpl(XDebuggerTree tree, @Nullable XDebuggerTreeNode parent, String name, @NotNull XValue value) {
    super(tree, parent, value);
    myName = name;

    value.computePresentation(this, XValuePlace.TREE);

    // add "Collecting" message only if computation is not yet done
    if (!isComputed()) {
      if (myName != null) {
        myText.append(myName, XDebuggerUIConstants.VALUE_NAME_ATTRIBUTES);
        myText.append(XDebuggerUIConstants.EQ_TEXT, SimpleTextAttributes.REGULAR_ATTRIBUTES);
      }
      myText.append(XDebuggerUIConstants.COLLECTING_DATA_MESSAGE, XDebuggerUIConstants.COLLECTING_DATA_HIGHLIGHT_ATTRIBUTES);
    }
  }

  @Override
  public void setPresentation(@Nullable Icon icon, @NonNls @Nullable String type, @NonNls @NotNull String value, boolean hasChildren) {
    XValueNodePresentationConfigurator.setPresentation(icon, type, value, hasChildren, this);
  }

  @Override
  public void setPresentation(@Nullable Icon icon, @NonNls @Nullable String type, @NonNls @NotNull String separator,
                              @NonNls @Nullable String value, boolean hasChildren) {
    XValueNodePresentationConfigurator.setPresentation(icon, type, separator, value, hasChildren, this);
  }

  @Override
  public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
    XValueNodePresentationConfigurator.setPresentation(icon, presentation, hasChildren, this);
  }

  @Override
  public void applyPresentation(@Nullable Icon icon, @NotNull XValuePresentation valuePresentation, boolean hasChildren) {
    // extra check for obsolete nodes - tree root was changed
    // too dangerous to put this into isObsolete - it is called from anywhere, not only EDT
    if (isObsolete()) return;

    setIcon(icon);
    myValuePresentation = valuePresentation;
    myRawValue = XValuePresentationUtil.computeValueText(valuePresentation);
    if (XDebuggerSettingsManager.getInstance().getDataViewSettings().isShowValuesInline()) {
      updateInlineDebuggerData();
    }
    updateText();
    setLeaf(!hasChildren);
    fireNodeChanged();
    myTree.nodeLoaded(this, myName);
  }

  public void updateInlineDebuggerData() {
    try {
      XDebugSession session = XDebugView.getSession(getTree());
      final XSourcePosition debuggerPosition = session == null ? null : session.getCurrentPosition();
      if (debuggerPosition == null) {
        return;
      }

      final XInlineDebuggerDataCallback callback = new XInlineDebuggerDataCallback() {
        @Override
        public void computed(XSourcePosition position) {
          if (position == null) return;
          VirtualFile file = position.getFile();
          // filter out values from other files
          if (!Comparing.equal(debuggerPosition.getFile(), file)) {
            return;
          }
          final Document document = FileDocumentManager.getInstance().getDocument(file);
          if (document == null) return;

          XVariablesView.InlineVariablesInfo data = myTree.getProject().getUserData(XVariablesView.DEBUG_VARIABLES);
          final TObjectLongHashMap<VirtualFile> timestamps = myTree.getProject().getUserData(XVariablesView.DEBUG_VARIABLES_TIMESTAMPS);
          if (data == null || timestamps == null) {
            return;
          }

          if (!showAsInlay(file, position)) {
            data.put(file, position, XValueNodeImpl.this);
            timestamps.put(file, document.getModificationStamp());

            myTree.updateEditor();
          }
        }
      };

      if (getValueContainer().computeInlineDebuggerData(callback) == ThreeState.UNSURE) {
        getValueContainer().computeSourcePosition(callback::computed);
      }
    }
    catch (Exception ignore) {
    }
  }

  private boolean showAsInlay(VirtualFile file, XSourcePosition position) {
    if (!Registry.is("debugger.show.values.inplace")) return false;
    XDebugSession session = XDebuggerManager.getInstance(myTree.getProject()).getCurrentSession();
    if (session == null) return false;
    XSourcePosition currentPosition = session.getCurrentPosition();
    if (currentPosition == null) return false;
    if (!currentPosition.getFile().equals(position.getFile()) || currentPosition.getLine() != position.getLine()) return false;
    XValue container = getValueContainer();
    if (!(container instanceof XValueWithInlinePresentation)) return false;
    String presentation = ((XValueWithInlinePresentation)container).computeInlinePresentation();
    if (presentation == null) return false;
    UIUtil.invokeLaterIfNeeded(() -> {
      FileEditor editor = FileEditorManager.getInstance(myTree.getProject()).getSelectedEditor(file);
      if (editor instanceof TextEditor) {
        Editor e = ((TextEditor)editor).getEditor();
        List<Inlay> existing = e.getInlayModel().getInlineElementsInRange(position.getOffset(), position.getOffset());
        for (Inlay inlay : existing) {
          if (inlay.getRenderer() instanceof MyRenderer) {
            Disposer.dispose(inlay);
          }
        }
        CharSequence text = e.getDocument().getImmutableCharSequence();
        int offset = position.getOffset();
        while (offset < text.length() && Character.isJavaIdentifierPart(text.charAt(offset))) offset++;

        e.getInlayModel().addInlineElement(offset, new MyRenderer(presentation));
      }
    });
    return true;
  }

  @Override
  public void setFullValueEvaluator(@NotNull final XFullValueEvaluator fullValueEvaluator) {
    invokeNodeUpdate(() -> {
      myFullValueEvaluator = fullValueEvaluator;
      fireNodeChanged();
    });
  }

  private void updateText() {
    myText.clear();
    XValueMarkers<?, ?> markers = myTree.getValueMarkers();
    if (markers != null) {
      ValueMarkup markup = markers.getMarkup(getValueContainer());
      if (markup != null) {
        myText.append("[" + markup.getText() + "] ", new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, markup.getColor()));
      }
    }
    appendName();
    buildText(myValuePresentation, myText);
  }

  private void appendName() {
    if (!StringUtil.isEmpty(myName)) {
      SimpleTextAttributes attributes = myChanged ? XDebuggerUIConstants.CHANGED_VALUE_ATTRIBUTES : XDebuggerUIConstants.VALUE_NAME_ATTRIBUTES;
      XValuePresentationUtil.renderValue(myName, myText, attributes, MAX_NAME_LENGTH, null);
    }
  }

  public static void buildText(@NotNull XValuePresentation valuePresenter, @NotNull ColoredTextContainer text) {
    buildText(valuePresenter, text, true);
  }

  public static void buildText(@NotNull XValuePresentation valuePresenter, @NotNull ColoredTextContainer text, boolean appendSeparator) {
    if (appendSeparator) {
      XValuePresentationUtil.appendSeparator(text, valuePresenter.getSeparator());
    }
    String type = valuePresenter.getType();
    if (type != null) {
      text.append("{" + type + "} ", XDebuggerUIConstants.TYPE_ATTRIBUTES);
    }
    valuePresenter.renderValue(new XValueTextRendererImpl(text));
  }

  @Override
  public void markChanged() {
    if (myChanged) return;

    ApplicationManager.getApplication().assertIsDispatchThread();
    myChanged = true;
    if (myName != null && myValuePresentation != null) {
      updateText();
      fireNodeChanged();
    }
  }

  @Nullable
  public XFullValueEvaluator getFullValueEvaluator() {
    return myFullValueEvaluator;
  }

  @Nullable
  @Override
  protected XDebuggerTreeNodeHyperlink getLink() {
    if (myFullValueEvaluator != null) {
      return new XDebuggerTreeNodeHyperlink(myFullValueEvaluator.getLinkText()) {
        @Override
        public boolean alwaysOnScreen() {
          return true;
        }

        @Override
        public void onClick(MouseEvent event) {
          if (myFullValueEvaluator.isShowValuePopup()) {
            DebuggerUIUtil.showValuePopup(myFullValueEvaluator, event, myTree.getProject(), null);
          }
          else {
            new HeadlessValueEvaluationCallback(XValueNodeImpl.this).startFetchingValue(myFullValueEvaluator);
          }
          event.consume();
        }
      };
    }
    return null;
  }

  @Override
  @Nullable
  public String getName() {
    return myName;
  }

  @Nullable
  public XValuePresentation getValuePresentation() {
    return myValuePresentation;
  }

  @Override
  @Nullable
  public String getRawValue() {
    return myRawValue;
  }

  @Override
  public boolean isComputed() {
    return myValuePresentation != null;
  }

  public void setValueModificationStarted() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    myRawValue = null;
    myText.clear();
    appendName();
    XValuePresentationUtil.appendSeparator(myText, myValuePresentation.getSeparator());
    myText.append(XDebuggerUIConstants.MODIFYING_VALUE_MESSAGE, XDebuggerUIConstants.MODIFYING_VALUE_HIGHLIGHT_ATTRIBUTES);
    setLeaf(true);
    fireNodeStructureChanged();
  }

  @Override
  public String toString() {
    return getName();
  }

  public static class MyRenderer implements EditorCustomElementRenderer {
    private final String myText;

    private MyRenderer(String text) {
      myText = "(" + text + ")";
    }

    private static FontInfo getFontInfo(@NotNull Editor editor) {
      EditorColorsScheme colorsScheme = editor.getColorsScheme();
      FontPreferences fontPreferences = colorsScheme.getFontPreferences();
      TextAttributes attributes = editor.getColorsScheme().getAttributes(DebuggerColors.INLINED_VALUES_EXECUTION_LINE);
      int fontStyle = attributes == null ? Font.PLAIN : attributes.getFontType();
      return ComplementaryFontsRegistry.getFontAbleToDisplay('a', fontStyle, fontPreferences);
    }

    @Override
    public int calcWidthInPixels(@NotNull Editor editor) {
      FontInfo fontInfo = getFontInfo(editor);
      return fontInfo.fontMetrics().stringWidth(myText);
    }

    @Override
    public void paint(@NotNull Editor editor, @NotNull Graphics g, @NotNull Rectangle r) {
      TextAttributes attributes = editor.getColorsScheme().getAttributes(DebuggerColors.INLINED_VALUES_EXECUTION_LINE);
      if (attributes == null) return;
      Color fgColor = attributes.getForegroundColor();
      if (fgColor == null) return;
      g.setColor(fgColor);
      FontInfo fontInfo = getFontInfo(editor);
      g.setFont(fontInfo.getFont());
      FontMetrics metrics = fontInfo.fontMetrics();
      g.drawString(myText, r.x, r.y + metrics.getAscent());
    }
  }
}