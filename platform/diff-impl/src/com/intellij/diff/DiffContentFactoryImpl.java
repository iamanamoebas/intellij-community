/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.diff;

import com.intellij.diff.actions.DocumentFragmentContent;
import com.intellij.diff.contents.*;
import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.ide.highlighter.ArchiveFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.BinaryLightVirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.LineSeparator;
import com.intellij.util.PathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

public class DiffContentFactoryImpl extends DiffContentFactory {
  public static final Logger LOG = Logger.getInstance(DiffContentFactoryImpl.class);

  @NotNull
  public static DiffContentFactoryImpl getInstanceImpl() {
    return (DiffContentFactoryImpl)DiffContentFactory.getInstance();
  }

  @Override
  @NotNull
  public EmptyContent createEmpty() {
    return new EmptyContent();
  }

  @Override
  @NotNull
  public DocumentContent create(@NotNull String text) {
    return create(text, (FileType)null);
  }

  @Override
  @NotNull
  public DocumentContent create(@NotNull String text, @Nullable FileType type) {
    return create(text, type, true);
  }

  @Override
  @NotNull
  public DocumentContent create(@NotNull String text, @Nullable FileType type, boolean respectLineSeparators) {
    return createImpl(text, type, null, null, respectLineSeparators, true);
  }

  @Override
  @NotNull
  public DocumentContent create(@NotNull String text, @Nullable VirtualFile highlightFile) {
    return createImpl(text, highlightFile != null ? highlightFile.getFileType() : null, highlightFile, null, true, true);
  }

  @NotNull
  @Override
  public DocumentContent create(@NotNull String text, @Nullable DocumentContent referent) {
    if (referent == null) return create(text);
    return createImpl(text, referent.getContentType(), referent.getHighlightFile(), null, false, true);
  }

  @NotNull
  @Override
  public DocumentContent create(@NotNull Document document, @Nullable DocumentContent referent) {
    if (referent == null) return new DocumentContentImpl(document);
    return new DocumentContentImpl(document, referent.getContentType(), referent.getHighlightFile(), null, null, null);
  }

  @Override
  @NotNull
  public DocumentContent create(@Nullable Project project, @NotNull Document document) {
    return create(project, document, (FileType)null);
  }

  @Override
  @NotNull
  public DocumentContent create(@Nullable Project project, @NotNull Document document, @Nullable FileType fileType) {
    VirtualFile file = FileDocumentManager.getInstance().getFile(document);
    if (file == null) return new DocumentContentImpl(document, fileType, null, null, null, null);
    return create(project, document, file);
  }

  @Override
  @NotNull
  public DocumentContent create(@Nullable Project project, @NotNull Document document, @Nullable VirtualFile file) {
    if (file != null) return new FileDocumentContentImpl(project, document, file);
    return new DocumentContentImpl(document);
  }

  @Override
  @NotNull
  public DiffContent create(@Nullable Project project, @NotNull VirtualFile file) {
    if (file.isDirectory()) return new DirectoryContentImpl(project, file);
    DocumentContent content = createDocument(project, file);
    if (content != null) return content;
    return new FileContentImpl(project, file);
  }

  @Override
  @Nullable
  public DocumentContent createDocument(@Nullable Project project, @NotNull final VirtualFile file) {
    // TODO: add notification, that file is decompiled ?
    if (file.isDirectory()) return null;
    Document document = ReadAction.compute(() -> {
      return FileDocumentManager.getInstance().getDocument(file);
    });
    if (document == null) return null;
    return new FileDocumentContentImpl(project, document, file);
  }

  @Override
  @Nullable
  public FileContent createFile(@Nullable Project project, @NotNull VirtualFile file) {
    if (file.isDirectory()) return null;
    return (FileContent)create(project, file);
  }

  @NotNull
  @Override
  public DocumentContent createFragment(@Nullable Project project, @NotNull Document document, @NotNull TextRange range) {
    DocumentContent content = create(project, document);
    return new DocumentFragmentContent(project, content, range);
  }

  @NotNull
  @Override
  public DocumentContent createFragment(@Nullable Project project, @NotNull DocumentContent content, @NotNull TextRange range) {
    return new DocumentFragmentContent(project, content, range);
  }

  @Override
  @NotNull
  public DiffContent createClipboardContent() {
    return createClipboardContent(null);
  }

  @Override
  @NotNull
  public DocumentContent createClipboardContent(@Nullable DocumentContent mainContent) {
    String text = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);

    FileType type = mainContent != null ? mainContent.getContentType() : null;
    VirtualFile highlightFile = mainContent != null ? mainContent.getHighlightFile() : null;

    DocumentContent content = createImpl(StringUtil.notNullize(text), type, highlightFile, null, true, false);
    content.putUserData(DiffUserDataKeysEx.FILE_NAME, "Clipboard.txt");
    return content;
  }

  @NotNull
  private static DocumentContent createImpl(@NotNull String text,
                                            @Nullable FileType type,
                                            @Nullable VirtualFile highlightFile,
                                            @Nullable Charset charset,
                                            boolean respectLineSeparators,
                                            boolean readOnly) {
    // TODO: detect invalid (different across the file) separators ?
    LineSeparator separator = respectLineSeparators ? StringUtil.detectSeparators(text) : null;
    Document document = EditorFactory.getInstance().createDocument(StringUtil.convertLineSeparators(text));
    if (readOnly) document.setReadOnly(true);
    return new DocumentContentImpl(document, type, highlightFile, separator, charset, null);
  }

  @NotNull
  public DiffContent createFromBytes(@Nullable Project project,
                                     @NotNull FilePath filePath,
                                     @NotNull byte[] content) throws IOException {
    if (filePath.getFileType().isBinary()) {
      return DiffContentFactory.getInstance().createBinary(project, filePath.getName(), filePath.getFileType(), content);
    }

    return FileAwareDocumentContent.create(project, content, filePath);
  }

  @Override
  @NotNull
  public DiffContent createFromBytes(@Nullable Project project,
                                     @NotNull VirtualFile highlightFile,
                                     @NotNull byte[] content) throws IOException {
    // TODO: check if FileType.UNKNOWN is actually a text ?
    if (highlightFile.getFileType().isBinary()) {
      return DiffContentFactory.getInstance().createBinary(project, highlightFile.getName(), highlightFile.getFileType(), content);
    }

    return FileAwareDocumentContent.create(project, content, highlightFile);
  }

  @Override
  @NotNull
  public DiffContent createBinary(@Nullable Project project,
                                  @NotNull String fileName,
                                  @NotNull FileType type,
                                  @NotNull byte[] content) throws IOException {
    // workaround - our JarFileSystem and decompilers can't process non-local files
    boolean useTemporalFile = type instanceof ArchiveFileType || BinaryFileTypeDecompilers.INSTANCE.forFileType(type) != null;

    VirtualFile file;
    if (useTemporalFile) {
      file = createTemporalFile(project, "tmp", fileName, content);
    }
    else {
      file = new BinaryLightVirtualFile(fileName, type, content);
      file.setWritable(false);
    }

    return create(project, file);
  }

  @NotNull
  private static VirtualFile createTemporalFile(@Nullable Project project,
                                                @NotNull String prefix,
                                                @NotNull String suffix,
                                                @NotNull byte[] content) throws IOException {
    File tempFile = FileUtil.createTempFile(PathUtil.suggestFileName(prefix + "_", true, false),
                                            PathUtil.suggestFileName("_" + suffix, true, false), true);
    if (content.length != 0) {
      FileUtil.writeToFile(tempFile, content);
    }
    if (!tempFile.setWritable(false, false)) LOG.warn("Can't set writable attribute of temporal file");

    VirtualFile file = VfsUtil.findFileByIoFile(tempFile, true);
    if (file == null) {
      throw new IOException("Can't create temp file for revision content");
    }
    VfsUtil.markDirtyAndRefresh(true, true, true, file);
    return file;
  }

  @NotNull
  public static Document createDocument(@Nullable Project project,
                                        @NotNull String content,
                                        @Nullable FileType fileType,
                                        @Nullable String fileName,
                                        boolean readOnly) {
    if (project != null && !project.isDefault() &&
        fileType != null && !fileType.isBinary() &&
        Registry.is("diff.enable.psi.highlighting")) {
      if (fileName == null) {
        fileName = "diff." + StringUtil.defaultIfEmpty(fileType.getDefaultExtension(), "txt");
      }

      Document document = createPsiDocument(project, content, fileType, fileName, readOnly);
      if (document != null) return document;
    }

    Document document = EditorFactory.getInstance().createDocument(content);
    document.setReadOnly(readOnly);
    return document;
  }

  @Nullable
  private static Document createPsiDocument(@NotNull Project project,
                                            @NotNull String content,
                                            @NotNull FileType fileType,
                                            @NotNull String fileName,
                                            boolean readOnly) {
    return ReadAction.compute(() -> {
      LightVirtualFile file = new LightVirtualFile(fileName, DiffPsiFileType.INSTANCE, content);
      file.setWritable(!readOnly);

      file.putUserData(DiffPsiFileType.ORIGINAL_FILE_TYPE_KEY, fileType);

      Document document = FileDocumentManager.getInstance().getDocument(file);
      if (document == null) return null;

      PsiDocumentManager.getInstance(project).getPsiFile(document);

      return document;
    });
  }
}
