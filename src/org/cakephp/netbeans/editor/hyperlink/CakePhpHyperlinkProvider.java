/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2012 Oracle and/or its affiliates. All rights reserved.
 *
 * Oracle and Java are registered trademarks of Oracle and/or its affiliates.
 * Other names may be trademarks of their respective owners.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common
 * Development and Distribution License("CDDL") (collectively, the
 * "License"). You may not use this file except in compliance with the
 * License. You can obtain a copy of the License at
 * http://www.netbeans.org/cddl-gplv2.html
 * or nbbuild/licenses/CDDL-GPL-2-CP. See the License for the
 * specific language governing permissions and limitations under the
 * License.  When distributing the software, include this License Header
 * Notice in each file and include the License file at
 * nbbuild/licenses/CDDL-GPL-2-CP.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the GPL Version 2 section of the License file that
 * accompanied this code. If applicable, add the following below the
 * License Header, with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * If you wish your version of this file to be governed by only the CDDL
 * or only the GPL Version 2, indicate your decision by adding
 * "[Contributor] elects to include this software in this distribution
 * under the [CDDL or GPL Version 2] license." If you do not indicate a
 * single choice of license, a recipient has the option to distribute
 * your version of this file under either the CDDL, the GPL Version 2 or
 * to extend the choice of license to its licensees as provided above.
 * However, if you add GPL Version 2 code and therefore, elected the GPL
 * Version 2 license, then the option applies only if the new code is
 * made subject to such option by the copyright holder.
 *
 * Contributor(s):
 *
 * Portions Copyrighted 2012 Sun Microsystems, Inc.
 */
package org.cakephp.netbeans.editor.hyperlink;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.swing.text.AbstractDocument;
import javax.swing.text.Document;
import org.cakephp.netbeans.modules.CakePhpModule;
import org.cakephp.netbeans.modules.CakePhpModule.DIR_TYPE;
import org.cakephp.netbeans.modules.CakePhpModule.FILE_TYPE;
import org.cakephp.netbeans.util.CakePhpUtils;
import org.netbeans.api.annotations.common.NonNull;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.api.lexer.Token;
import org.netbeans.api.lexer.TokenHierarchy;
import org.netbeans.api.lexer.TokenSequence;
import org.netbeans.lib.editor.hyperlink.spi.HyperlinkProviderExt;
import org.netbeans.lib.editor.hyperlink.spi.HyperlinkType;
import org.netbeans.modules.csl.api.UiUtils;
import org.netbeans.modules.editor.NbEditorUtilities;
import org.netbeans.modules.php.api.phpmodule.PhpModule;
import org.netbeans.modules.php.api.util.FileUtils;
import org.netbeans.modules.php.editor.lexer.LexUtilities;
import org.netbeans.modules.php.editor.lexer.PHPTokenId;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectNotFoundException;
import org.openide.util.Exceptions;

/**
 *
 * @author junichi11
 */
@MimeRegistration(mimeType = FileUtils.PHP_MIME_TYPE, service = HyperlinkProviderExt.class)
public class CakePhpHyperlinkProvider implements HyperlinkProviderExt {

    private FileObject targetFile;
    private String targetText;
    private int targetStart;
    private int targetEnd;
    private String tooltipText;
    private static final List<String> targetMethods = Arrays.asList("element"); // NOI18N

    @Override
    public Set<HyperlinkType> getSupportedHyperlinkTypes() {
        return Collections.singleton(HyperlinkType.GO_TO_DECLARATION);
    }

    @Override
    public String getTooltipText(Document dcmnt, int i, HyperlinkType hyperlinkType) {
        return tooltipText;
    }

    @Override
    public boolean isHyperlinkPoint(Document doc, int offset, HyperlinkType hyperlinkType) {
        return verifyState(doc, offset);
    }

    @SuppressWarnings("unchecked")
    public boolean verifyState(Document doc, int offset) {
        PhpModule phpModule = PhpModule.Factory.inferPhpModule();
        if (phpModule == null || !CakePhpUtils.isCakePHP(phpModule)) {
            return false;
        }

        TokenSequence<PHPTokenId> ts = null;
        if (doc instanceof AbstractDocument) {
            AbstractDocument ad = (AbstractDocument) doc;
            ad.readLock();
            try {
                TokenHierarchy<Document> hierarchy = TokenHierarchy.get(doc);
                ts = hierarchy.tokenSequence(PHPTokenId.language());
            } finally {
                ad.readUnlock();
            }
        }

        if (ts == null) {
            return false;
        }

        ts.move(offset);
        ts.moveNext();
        Token<PHPTokenId> token = ts.token();
        PHPTokenId id = token.id();
        int newOffset = ts.offset();
        String methodName = getMethodName(ts);
        if (!isTarget(methodName)) {
            return false;
        }

        if (id == PHPTokenId.PHP_CONSTANT_ENCAPSED_STRING) {
            targetText = token.text().toString();
            if (targetText.length() > 2) {
                targetText = CakePhpUtils.detachQuotes(targetText);
            } else {
                targetText = ""; // NOI18N
                return false;
            }

            targetFile = getTargetFile(doc, methodName);
            if (targetFile != null) {
                targetStart = newOffset + 1;
                targetEnd = targetStart + targetText.length();
                tooltipText = getTooltipText(phpModule, targetFile);
                return true;
            }
        }

        return false;
    }

    private FileObject getTargetFile(Document doc, String methodName) {
        if (isElement(methodName)) {
            return getElementFile(doc);
        }
        return null;
    }

    /**
     * Get element file.
     *
     * @param doc
     * @return element file
     */
    private FileObject getElementFile(Document doc) {
        CakePhpModule module = CakePhpModule.forPhpModule(PhpModule.Factory.inferPhpModule());
        if (module == null) {
            return null;
        }
        FileObject elementFile = null;

        // check plugin : Plugin.element
        String[] pluginSplit = CakePhpUtils.pluginSplit(targetText);
        if (pluginSplit.length == 2) {
            elementFile = module.getFile(CakePhpModule.ALL_PLUGINS, FILE_TYPE.ELEMENT, pluginSplit[1], pluginSplit[0]);
        } else if (pluginSplit.length == 1) {
            FileObject currentFileObject = NbEditorUtilities.getFileObject(doc);
            String pluginName = module.getCurrentPluginName(currentFileObject);
            DIR_TYPE dirType = module.getDirectoryType(currentFileObject);
            elementFile = module.getFile(Arrays.asList(dirType, DIR_TYPE.CORE), FILE_TYPE.ELEMENT, targetText, pluginName);
        }

        return elementFile;
    }

    /**
     * Get text for tooltip. Return a path from source direcotry. If target file
     * doesn't exist under the source directory, just return the file path.
     *
     * @param phpModule
     * @param target
     * @return tooltip text
     */
    private String getTooltipText(@NonNull PhpModule phpModule, @NonNull FileObject target) {
        String targetPath = target.getPath();
        FileObject sourceDirectory = phpModule.getSourceDirectory();
        if (sourceDirectory != null) {
            String sourceDirectoryPath = sourceDirectory.getPath();
            return targetPath.replace(sourceDirectoryPath, ""); // NOI18N
        }
        return targetPath;
    }

    @Override
    public int[] getHyperlinkSpan(Document doc, int offset, HyperlinkType hyperlinkType) {
        if (targetFile != null) {
            return new int[]{targetStart, targetEnd};
        }
        return new int[0];
    }

    @Override
    public void performClickAction(Document doc, int offset, HyperlinkType hyperlinkType) {
        if (targetFile != null) {
            try {
                DataObject dataObject = DataObject.find(targetFile);
                EditorCookie ec = dataObject.getLookup().lookup(EditorCookie.class
                );
                if (ec != null) {
                    ec.open();
                    return;
                }

                UiUtils.open(targetFile, 0);
            } catch (DataObjectNotFoundException ex) {
                Exceptions.printStackTrace(ex);
            }
        }
    }

    /**
     * Check target method name.
     *
     * @param methodName
     * @return {@code true} if target methos name, {@code false} otherwise.
     */
    private boolean isTarget(String methodName) {
        return targetMethods.contains(methodName);
    }

    /**
     * Check element method
     *
     * @param methodName
     * @return {@code true} if method name is element, {@code false} otherwise
     */
    private boolean isElement(String methodName) {
        return methodName.equals("element"); // NOI18N
    }

    /**
     * Get method name from current token sequence.
     *
     * @param ts token sequence
     * @return String if previous token has {@code PHPTokenId.PHP_STRING}, null
     * otherwiese.
     */
    private String getMethodName(TokenSequence<PHPTokenId> ts) {
        Token<? extends PHPTokenId> previousToken = LexUtilities.findPreviousToken(ts, Arrays.asList(PHPTokenId.PHP_STRING, PHPTokenId.PHP_SEMICOLON));
        if (previousToken == null || previousToken.id() == PHPTokenId.PHP_SEMICOLON) {
            return null;
        }
        return previousToken.text().toString();
    }
}
