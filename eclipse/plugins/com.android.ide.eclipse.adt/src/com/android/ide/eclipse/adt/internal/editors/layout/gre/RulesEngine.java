/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ide.eclipse.adt.internal.editors.layout.gre;

import static com.android.ide.common.layout.LayoutConstants.ANDROID_WIDGET_PREFIX;
import static com.android.ide.eclipse.adt.internal.editors.layout.descriptors.LayoutDescriptors.VIEW_MERGE;
import static com.android.sdklib.SdkConstants.CLASS_FRAGMENT;
import static com.android.sdklib.SdkConstants.CLASS_V4_FRAGMENT;

import com.android.ide.common.api.DropFeedback;
import com.android.ide.common.api.IClientRulesEngine;
import com.android.ide.common.api.IDragElement;
import com.android.ide.common.api.IGraphics;
import com.android.ide.common.api.INode;
import com.android.ide.common.api.IValidator;
import com.android.ide.common.api.IViewMetadata;
import com.android.ide.common.api.IViewRule;
import com.android.ide.common.api.InsertType;
import com.android.ide.common.api.MenuAction;
import com.android.ide.common.api.Point;
import com.android.ide.common.layout.ViewRule;
import com.android.ide.common.resources.ResourceRepository;
import com.android.ide.eclipse.adt.AdtPlugin;
import com.android.ide.eclipse.adt.internal.editors.AndroidXmlEditor;
import com.android.ide.eclipse.adt.internal.editors.descriptors.ElementDescriptor;
import com.android.ide.eclipse.adt.internal.editors.layout.descriptors.ViewElementDescriptor;
import com.android.ide.eclipse.adt.internal.editors.layout.gle2.GraphicalEditorPart;
import com.android.ide.eclipse.adt.internal.editors.layout.gle2.LayoutCanvas;
import com.android.ide.eclipse.adt.internal.editors.layout.gle2.SelectionManager;
import com.android.ide.eclipse.adt.internal.editors.layout.gle2.SimpleElement;
import com.android.ide.eclipse.adt.internal.editors.layout.uimodel.UiViewElementNode;
import com.android.ide.eclipse.adt.internal.project.BaseProjectHelper;
import com.android.ide.eclipse.adt.internal.resources.CyclicDependencyValidator;
import com.android.ide.eclipse.adt.internal.resources.manager.ResourceManager;
import com.android.ide.eclipse.adt.internal.sdk.AndroidTargetData;
import com.android.ide.eclipse.adt.internal.sdk.ProjectState;
import com.android.ide.eclipse.adt.internal.sdk.Sdk;
import com.android.ide.eclipse.adt.internal.ui.MarginChooser;
import com.android.ide.eclipse.adt.internal.ui.ReferenceChooserDialog;
import com.android.ide.eclipse.adt.internal.ui.ResourceChooser;
import com.android.resources.ResourceType;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.internal.project.ProjectProperties;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.ui.IJavaElementSearchConstants;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jdt.ui.dialogs.ITypeInfoFilterExtension;
import org.eclipse.jdt.ui.dialogs.ITypeInfoRequestor;
import org.eclipse.jdt.ui.dialogs.TypeSelectionExtension;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.SelectionDialog;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The rule engine manages the layout rules and interacts with them.
 * There's one {@link RulesEngine} instance per layout editor.
 * Each instance has 2 sets of rules: the static ADT rules (shared across all instances)
 * and the project specific rules (local to the current instance / layout editor).
 */
public class RulesEngine {
    private final IProject mProject;
    private final Map<Object, IViewRule> mRulesCache = new HashMap<Object, IViewRule>();
    /**
     * The type of any upcoming node manipulations performed by the {@link IViewRule}s.
     * When actions are performed in the tool (like a paste action, or a drag from palette,
     * or a drag move within the canvas, etc), these are different types of inserts,
     * and we don't want to have the rules track them closely (and pass them back to us
     * in the {@link INode#insertChildAt} methods etc), so instead we track the state
     * here on behalf of the currently executing rule.
     */
    private InsertType mInsertType = InsertType.CREATE;

    /**
     * Class loader (or null) used to load user/project-specific IViewRule
     * classes
     */
    private ClassLoader mUserClassLoader;

    /**
     * Flag set when we've attempted to initialize the {@link #mUserClassLoader}
     * already
     */
    private boolean mUserClassLoaderInited;

    /**
     * The editor which owns this {@link RulesEngine}
     */
    private GraphicalEditorPart mEditor;

    /**
     * Creates a new {@link RulesEngine} associated with the selected project.
     * <p/>
     * The rules engine will look in the project for a tools jar to load custom view rules.
     *
     * @param editor the editor which owns this {@link RulesEngine}
     * @param project A non-null open project.
     */
    public RulesEngine(GraphicalEditorPart editor, IProject project) {
        mProject = project;
        mEditor = editor;
    }

    /**
     * Find out whether the given project has 3rd party ViewRules, and if so
     * return a ClassLoader which can locate them. If not, return null.
     * @param project The project to load user rules from
     * @return A class loader which can user view rules, or otherwise null
     */
    private static ClassLoader computeUserClassLoader(IProject project) {
        // Default place to locate layout rules. The user may also add to this
        // path by defining a config property specifying
        // additional .jar files to search via a the layoutrules.jars property.
        ProjectState state = Sdk.getProjectState(project);
        ProjectProperties projectProperties = state.getProperties();

        // Ensure we have the latest & greatest version of the properties.
        // This allows users to reopen editors in a running Eclipse instance
        // to get updated view rule jars
        projectProperties.reload();

        String path = projectProperties.getProperty(
                ProjectProperties.PROPERTY_RULES_PATH);

        if (path != null && path.length() > 0) {
            List<URL> urls = new ArrayList<URL>();
            String[] pathElements = path.split(File.pathSeparator);
            for (String pathElement : pathElements) {
                pathElement = pathElement.trim(); // Avoid problems with trailing whitespace etc
                File pathFile = new File(pathElement);
                if (!pathFile.isAbsolute()) {
                    pathFile = new File(project.getLocation().toFile(), pathElement);
                }
                // Directories and jar files are okay. Do we need to
                // validate the files here as .jar files?
                if (pathFile.isFile() || pathFile.isDirectory()) {
                    URL url;
                    try {
                        url = pathFile.toURI().toURL();
                        urls.add(url);
                    } catch (MalformedURLException e) {
                        AdtPlugin.log(IStatus.WARNING,
                                "Invalid URL: %1$s", //$NON-NLS-1$
                                e.toString());
                    }
                }
            }

            if (urls.size() > 0) {
                return new URLClassLoader(urls.toArray(new URL[urls.size()]),
                        RulesEngine.class.getClassLoader());
            }
        }

        return null;
    }

    /**
     * Returns the {@link IProject} on which the {@link RulesEngine} was created.
     */
    public IProject getProject() {
        return mProject;
    }

    /**
     * Called by the owner of the {@link RulesEngine} when it is going to be disposed.
     * This frees some resources, such as the project's folder monitor.
     */
    public void dispose() {
        clearCache();
    }

    /**
     * Invokes {@link IViewRule#getDisplayName()} on the rule matching the specified element.
     *
     * @param element The view element to target. Can be null.
     * @return Null if the rule failed, there's no rule or the rule does not want to override
     *   the display name. Otherwise, a string as returned by the rule.
     */
    public String callGetDisplayName(UiViewElementNode element) {
        // try to find a rule for this element's FQCN
        IViewRule rule = loadRule(element);

        if (rule != null) {
            try {
                return rule.getDisplayName();

            } catch (Exception e) {
                AdtPlugin.log(e, "%s.getDisplayName() failed: %s",
                        rule.getClass().getSimpleName(),
                        e.toString());
            }
        }

        return null;
    }

    /**
     * Invokes {@link IViewRule#getContextMenu(INode)} on the rule matching the specified element.
     *
     * @param selectedNode The node selected. Never null.
     * @return Null if the rule failed, there's no rule or the rule does not provide
     *   any custom menu actions. Otherwise, a list of {@link MenuAction}.
     */
    public List<MenuAction> callGetContextMenu(NodeProxy selectedNode) {
        // try to find a rule for this element's FQCN
        IViewRule rule = loadRule(selectedNode.getNode());

        if (rule != null) {
            try {
                mInsertType = InsertType.CREATE;
                return rule.getContextMenu(selectedNode);

            } catch (Exception e) {
                AdtPlugin.log(e, "%s.getContextMenu() failed: %s",
                        rule.getClass().getSimpleName(),
                        e.toString());
            }
        }

        return null;
    }

    /**
     * Invokes {@link IViewRule#getContextMenu(INode)} on the rule matching the specified element.
     *
     * @param actions The list of actions to add layout actions into
     * @param parentNode The layout node
     * @param children The selected children of the node, if any (used to initialize values
     *    of child layout controls, if applicable)
     * @return Null if the rule failed, there's no rule or the rule does not provide
     *   any custom menu actions. Otherwise, a list of {@link MenuAction}.
     */
    public List<MenuAction> callAddLayoutActions(List<MenuAction> actions,
            NodeProxy parentNode, List<NodeProxy> children ) {
        // try to find a rule for this element's FQCN
        IViewRule rule = loadRule(parentNode.getNode());

        if (rule != null) {
            try {
                mInsertType = InsertType.CREATE;
                rule.addLayoutActions(actions, parentNode, children);
            } catch (Exception e) {
                AdtPlugin.log(e, "%s.getContextMenu() failed: %s",
                        rule.getClass().getSimpleName(),
                        e.toString());
            }
        }

        return null;
    }

    /**
     * Invokes {@link IViewRule#getSelectionHint(INode, INode)}
     * on the rule matching the specified element.
     *
     * @param parentNode The parent of the node selected. Never null.
     * @param childNode The child node that was selected. Never null.
     * @return a list of strings to be displayed, or null or empty to display nothing
     */
    public List<String> callGetSelectionHint(NodeProxy parentNode, NodeProxy childNode) {
        // try to find a rule for this element's FQCN
        IViewRule rule = loadRule(parentNode.getNode());

        if (rule != null) {
            try {
                return rule.getSelectionHint(parentNode, childNode);

            } catch (Exception e) {
                AdtPlugin.log(e, "%s.getSelectionHint() failed: %s",
                        rule.getClass().getSimpleName(),
                        e.toString());
            }
        }

        return null;
    }

    /**
     * Called when the d'n'd starts dragging over the target node.
     * If interested, returns a DropFeedback passed to onDrop/Move/Leave/Paint.
     * If not interested in drop, return false.
     * Followed by a paint.
     */
    public DropFeedback callOnDropEnter(NodeProxy targetNode,
            IDragElement[] elements) {
        // try to find a rule for this element's FQCN
        IViewRule rule = loadRule(targetNode.getNode());

        if (rule != null) {
            try {
                return rule.onDropEnter(targetNode, elements);

            } catch (Exception e) {
                AdtPlugin.log(e, "%s.onDropEnter() failed: %s",
                        rule.getClass().getSimpleName(),
                        e.toString());
            }
        }

        return null;
    }

    /**
     * Called after onDropEnter.
     * Returns a DropFeedback passed to onDrop/Move/Leave/Paint (typically same
     * as input one).
     */
    public DropFeedback callOnDropMove(NodeProxy targetNode,
            IDragElement[] elements,
            DropFeedback feedback,
            Point where) {
        // try to find a rule for this element's FQCN
        IViewRule rule = loadRule(targetNode.getNode());

        if (rule != null) {
            try {
                return rule.onDropMove(targetNode, elements, feedback, where);

            } catch (Exception e) {
                AdtPlugin.log(e, "%s.onDropMove() failed: %s",
                        rule.getClass().getSimpleName(),
                        e.toString());
            }
        }

        return null;
    }

    /**
     * Called when drop leaves the target without actually dropping
     */
    public void callOnDropLeave(NodeProxy targetNode,
            IDragElement[] elements,
            DropFeedback feedback) {
        // try to find a rule for this element's FQCN
        IViewRule rule = loadRule(targetNode.getNode());

        if (rule != null) {
            try {
                rule.onDropLeave(targetNode, elements, feedback);

            } catch (Exception e) {
                AdtPlugin.log(e, "%s.onDropLeave() failed: %s",
                        rule.getClass().getSimpleName(),
                        e.toString());
            }
        }
    }

    /**
     * Called when drop is released over the target to perform the actual drop.
     */
    public void callOnDropped(NodeProxy targetNode,
            IDragElement[] elements,
            DropFeedback feedback,
            Point where,
            InsertType insertType) {
        // try to find a rule for this element's FQCN
        IViewRule rule = loadRule(targetNode.getNode());

        if (rule != null) {
            try {
                mInsertType = insertType;
                rule.onDropped(targetNode, elements, feedback, where);

            } catch (Exception e) {
                AdtPlugin.log(e, "%s.onDropped() failed: %s",
                        rule.getClass().getSimpleName(),
                        e.toString());
            }
        }
    }

    /**
     * Called when a paint has been requested via DropFeedback.
     */
    public void callDropFeedbackPaint(IGraphics gc,
            NodeProxy targetNode,
            DropFeedback feedback) {
        if (gc != null && feedback != null && feedback.painter != null) {
            try {
                feedback.painter.paint(gc, targetNode, feedback);
            } catch (Exception e) {
                AdtPlugin.log(e, "DropFeedback.painter failed: %s",
                        e.toString());
            }
        }
    }

    /**
     * Called when pasting elements in an existing document on the selected target.
     *
     * @param targetNode The first node selected.
     * @param pastedElements The elements being pasted.
     */
    public void callOnPaste(NodeProxy targetNode, SimpleElement[] pastedElements) {
        // try to find a rule for this element's FQCN
        IViewRule rule = loadRule(targetNode.getNode());

        if (rule != null) {
            try {
                mInsertType = InsertType.PASTE;
                rule.onPaste(targetNode, pastedElements);

            } catch (Exception e) {
                AdtPlugin.log(e, "%s.onPaste() failed: %s",
                        rule.getClass().getSimpleName(),
                        e.toString());
            }
        }
    }

    // ---- Creation customizations ----

    /**
     * Invokes the create hooks ({@link IViewRule#onCreate},
     * {@link IViewRule#onChildInserted} when a new child has been created/pasted/moved, and
     * is inserted into a given parent. The parent may be null (for example when rendering
     * top level items for preview).
     *
     * @param editor the XML editor to apply edits to the model for (performed by view
     *            rules)
     * @param parentNode the parent XML node, or null if unknown
     * @param childNode the XML node of the new node, never null
     * @param overrideInsertType If not null, specifies an explicit insert type to use for
     *            edits made during the customization
     */
    public void callCreateHooks(
        AndroidXmlEditor editor,
        NodeProxy parentNode, NodeProxy childNode,
        InsertType overrideInsertType) {
        IViewRule parentRule = null;

        if (parentNode != null) {
            UiViewElementNode parentUiNode = parentNode.getNode();
            parentRule = loadRule(parentUiNode);
        }

        if (overrideInsertType != null) {
            mInsertType = overrideInsertType;
        }

        UiViewElementNode newUiNode = childNode.getNode();
        IViewRule childRule = loadRule(newUiNode);
        if (childRule != null || parentRule != null) {
            callCreateHooks(editor, mInsertType, parentRule, parentNode,
                    childRule, childNode);
        }
    }

    private static void callCreateHooks(
            final AndroidXmlEditor editor, final InsertType insertType,
            final IViewRule parentRule, final INode parentNode,
            final IViewRule childRule, final INode newNode) {
        // Notify the parent about the new child in case it wants to customize it
        // (For example, a ScrollView parent can go and set all its children's layout params to
        // fill the parent.)
        if (!editor.isEditXmlModelPending()) {
            editor.wrapUndoEditXmlModel("Customize creation", new Runnable() {
                public void run() {
                    callCreateHooks(editor, insertType,
                            parentRule, parentNode, childRule, newNode);
                }
            });
            return;
        }

        if (parentRule != null) {
            parentRule.onChildInserted(newNode, parentNode, insertType);
        }

        // Look up corresponding IViewRule, and notify the rule about
        // this create action in case it wants to customize the new object.
        // (For example, a rule for TabHosts can go and create a default child tab
        // when you create it.)
        if (childRule != null) {
            childRule.onCreate(newNode, parentNode, insertType);
        }
    }

    /**
     * Set the type of insert currently in progress
     *
     * @param insertType the insert type to use for the next operation
     */
    public void setInsertType(InsertType insertType) {
        mInsertType = insertType;
    }

    // ---- private ---

    /**
     * Returns the descriptor for the base View class.
     * This could be null if the SDK or the given platform target hasn't loaded yet.
     */
    private ViewElementDescriptor getBaseViewDescriptor() {
        Sdk currentSdk = Sdk.getCurrent();
        if (currentSdk != null) {
            IAndroidTarget target = currentSdk.getTarget(mProject);
            if (target != null) {
                AndroidTargetData data = currentSdk.getTargetData(target);
                return data.getLayoutDescriptors().getBaseViewDescriptor();
            }
        }
        return null;
    }

    /**
     * Clear the Rules cache. Calls onDispose() on each rule.
     */
    private void clearCache() {
        // The cache can contain multiple times the same rule instance for different
        // keys (e.g. the UiViewElementNode key vs. the FQCN string key.) So transfer
        // all values to a unique set.
        HashSet<IViewRule> rules = new HashSet<IViewRule>(mRulesCache.values());

        mRulesCache.clear();

        for (IViewRule rule : rules) {
            if (rule != null) {
                try {
                    rule.onDispose();
                } catch (Exception e) {
                    AdtPlugin.log(e, "%s.onDispose() failed: %s",
                            rule.getClass().getSimpleName(),
                            e.toString());
                }
            }
        }
    }

    /**
     * Load a rule using its descriptor. This will try to first load the rule using its
     * actual FQCN and if that fails will find the first parent that works in the view
     * hierarchy.
     */
    private IViewRule loadRule(UiViewElementNode element) {
        if (element == null) {
            return null;
        }

        String targetFqcn = null;
        ViewElementDescriptor targetDesc = null;

        ElementDescriptor d = element.getDescriptor();
        if (d instanceof ViewElementDescriptor) {
            targetDesc = (ViewElementDescriptor) d;
        }
        if (d == null || !(d instanceof ViewElementDescriptor)) {
            // This should not happen. All views should have some kind of *view* element
            // descriptor. Maybe the project is not complete and doesn't build or something.
            // In this case, we'll use the descriptor of the base android View class.
            targetDesc = getBaseViewDescriptor();
        }


        // Return the rule if we find it in the cache, even if it was stored as null
        // (which means we didn't find it earlier, so don't look for it again)
        IViewRule rule = mRulesCache.get(targetDesc);
        if (rule != null || mRulesCache.containsKey(targetDesc)) {
            return rule;
        }

        // Get the descriptor and loop through the super class hierarchy
        for (ViewElementDescriptor desc = targetDesc;
                desc != null;
                desc = desc.getSuperClassDesc()) {

            // Get the FQCN of this View
            String fqcn = desc.getFullClassName();
            if (fqcn == null) {
                // Shouldn't be happening.
                return null;
            }

            // The first time we keep the FQCN around as it's the target class we were
            // initially trying to load. After, as we move through the hierarchy, the
            // target FQCN remains constant.
            if (targetFqcn == null) {
                targetFqcn = fqcn;
            }

            if (fqcn.indexOf('.') == -1) {
                // Deal with unknown descriptors; these lack the full qualified path and
                // elements in the layout without a package are taken to be in the
                // android.widget package.
                fqcn = ANDROID_WIDGET_PREFIX + fqcn;
            }

            // Try to find a rule matching the "real" FQCN. If we find it, we're done.
            // If not, the for loop will move to the parent descriptor.
            rule = loadRule(fqcn, targetFqcn);
            if (rule != null) {
                // We found one.
                // As a side effect, loadRule() also cached the rule using the target FQCN.
                return rule;
            }
        }

        // Memorize in the cache that we couldn't find a rule for this descriptor
        mRulesCache.put(targetDesc, null);
        return null;
    }

    /**
     * Try to load a rule given a specific FQCN. This looks for an exact match in either
     * the ADT scripts or the project scripts and does not look at parent hierarchy.
     * <p/>
     * Once a rule is found (or not), it is stored in a cache using its target FQCN
     * so we don't try to reload it.
     * <p/>
     * The real FQCN is the actual rule class we're loading, e.g. "android.view.View"
     * where target FQCN is the class we were initially looking for, which might be the same as
     * the real FQCN or might be a derived class, e.g. "android.widget.TextView".
     *
     * @param realFqcn The FQCN of the rule class actually being loaded.
     * @param targetFqcn The FQCN of the class actually processed, which might be different from
     *          the FQCN of the rule being loaded.
     */
    private IViewRule loadRule(String realFqcn, String targetFqcn) {
        if (realFqcn == null || targetFqcn == null) {
            return null;
        }

        // Return the rule if we find it in the cache, even if it was stored as null
        // (which means we didn't find it earlier, so don't look for it again)
        IViewRule rule = mRulesCache.get(realFqcn);
        if (rule != null || mRulesCache.containsKey(realFqcn)) {
            return rule;
        }

        // Look for class via reflection
        try {
            // For now, we package view rules for the builtin Android views and
            // widgets with the tool in a special package, so look there rather
            // than in the same package as the widgets.
            String ruleClassName;
            ClassLoader classLoader;
            if (realFqcn.startsWith("android.") || //$NON-NLS-1$
                    realFqcn.equals(VIEW_MERGE) ||
                    // FIXME: Remove this special case as soon as we pull
                    // the MapViewRule out of this code base and bundle it
                    // with the add ons
                    realFqcn.startsWith("com.google.android.maps.")) { //$NON-NLS-1$
                // This doesn't handle a case where there are name conflicts
                // (e.g. where there are multiple different views with the same
                // class name and only differing in package names, but that's a
                // really bad practice in the first place, and if that situation
                // should come up in the API we can enhance this algorithm.
                String packageName = ViewRule.class.getName();
                packageName = packageName.substring(0, packageName.lastIndexOf('.'));
                classLoader = RulesEngine.class.getClassLoader();
                int dotIndex = realFqcn.lastIndexOf('.');
                String baseName = realFqcn.substring(dotIndex+1);
                // Capitalize rule class name to match naming conventions, if necessary (<merge>)
                if (Character.isLowerCase(baseName.charAt(0))) {
                    baseName = Character.toUpperCase(baseName.charAt(0)) + baseName.substring(1);
                }
                ruleClassName = packageName + "." + //$NON-NLS-1$
                    baseName + "Rule"; //$NON-NLS-1$

            } else {
                // Initialize the user-classpath for 3rd party IViewRules, if necessary
                if (mUserClassLoader == null) {
                    // Only attempt to load rule paths once (per RulesEngine instance);
                    if (!mUserClassLoaderInited) {
                        mUserClassLoaderInited = true;
                        mUserClassLoader = computeUserClassLoader(mProject);
                    }

                    if (mUserClassLoader == null) {
                        // The mUserClassLoader can be null; this is the typical scenario,
                        // when the user is only using builtin layout rules.
                        // This means however we can't resolve this fqcn since it's not
                        // in the name space of the builtin rules.
                        mRulesCache.put(realFqcn, null);
                        return null;
                    }
                }

                // For other (3rd party) widgets, look in the same package (though most
                // likely not in the same jar!)
                ruleClassName = realFqcn + "Rule"; //$NON-NLS-1$
                classLoader = mUserClassLoader;
            }

            Class<?> clz = Class.forName(ruleClassName, true, classLoader);
            rule = (IViewRule) clz.newInstance();
            return initializeRule(rule, targetFqcn);
        } catch (ClassNotFoundException ex) {
            // Not an unexpected error - this means that there isn't a helper for this
            // class.
        } catch (InstantiationException e) {
            // This is NOT an expected error: fail.
            AdtPlugin.log(e, "load rule error (%s): %s", realFqcn, e.toString());
        } catch (IllegalAccessException e) {
            // This is NOT an expected error: fail.
            AdtPlugin.log(e, "load rule error (%s): %s", realFqcn, e.toString());
        }

        // Memorize in the cache that we couldn't find a rule for this real FQCN
        mRulesCache.put(realFqcn, null);
        return null;
    }

    /**
     * Initialize a rule we just loaded. The rule has a chance to examine the target FQCN
     * and bail out.
     * <p/>
     * Contract: the rule is not in the {@link #mRulesCache} yet and this method will
     * cache it using the target FQCN if the rule is accepted.
     * <p/>
     * The real FQCN is the actual rule class we're loading, e.g. "android.view.View"
     * where target FQCN is the class we were initially looking for, which might be the same as
     * the real FQCN or might be a derived class, e.g. "android.widget.TextView".
     *
     * @param rule A rule freshly loaded.
     * @param targetFqcn The FQCN of the class actually processed, which might be different from
     *          the FQCN of the rule being loaded.
     * @return The rule if accepted, or null if the rule can't handle that FQCN.
     */
    private IViewRule initializeRule(IViewRule rule, String targetFqcn) {

        try {
            if (rule.onInitialize(targetFqcn, new ClientRulesEngineImpl(targetFqcn))) {
                // Add it to the cache and return it
                mRulesCache.put(targetFqcn, rule);
                return rule;
            } else {
                rule.onDispose();
            }
        } catch (Exception e) {
            AdtPlugin.log(e, "%s.onInit() failed: %s",
                    rule.getClass().getSimpleName(),
                    e.toString());
        }

        return null;
    }

    /**
     * Implementation of {@link IClientRulesEngine}. This provide {@link IViewRule} clients
     * with a few methods they can use to use functionality from this {@link RulesEngine}.
     */
    private class ClientRulesEngineImpl implements IClientRulesEngine {
        private final String mFqcn;

        public ClientRulesEngineImpl(String fqcn) {
            mFqcn = fqcn;
        }

        public String getFqcn() {
            return mFqcn;
        }

        public void debugPrintf(String msg, Object... params) {
            AdtPlugin.printToConsole(
                    mFqcn == null ? "<unknown>" : mFqcn,
                    String.format(msg, params)
                    );
        }

        public IViewRule loadRule(String fqcn) {
            return RulesEngine.this.loadRule(fqcn, fqcn);
        }

        public void displayAlert(String message) {
            MessageDialog.openInformation(
                    AdtPlugin.getDisplay().getActiveShell(),
                    mFqcn,  // title
                    message);
        }

        public String displayInput(String message, String value, final IValidator filter) {
            IInputValidator validator = null;
            if (filter != null) {
                validator = new IInputValidator() {
                    public String isValid(String newText) {
                        // IValidator has the same interface as SWT's IInputValidator
                        try {
                            return filter.validate(newText);
                        } catch (Exception e) {
                            AdtPlugin.log(e, "Custom validator failed: %s", e.toString());
                            return ""; //$NON-NLS-1$
                        }
                    }
                };
            }

            InputDialog d = new InputDialog(
                        AdtPlugin.getDisplay().getActiveShell(),
                        mFqcn,  // title
                        message,
                        value == null ? "" : value, //$NON-NLS-1$
                        validator);
            if (d.open() == Window.OK) {
                return d.getValue();
            }
            return null;
        }

        public IViewMetadata getMetadata(final String fqcn) {
            return new IViewMetadata() {
                public String getDisplayName() {
                    // This also works when there is no "."
                    return fqcn.substring(fqcn.lastIndexOf('.') + 1);
                }

                public FillPreference getFillPreference() {
                    return ViewMetadataRepository.get().getFillPreference(fqcn);
                }
            };
        }

        public int getMinApiLevel() {
            Sdk currentSdk = Sdk.getCurrent();
            if (currentSdk != null) {
                IAndroidTarget target = currentSdk.getTarget(mEditor.getProject());
                return target.getVersion().getApiLevel();
            }

            return -1;
        }

        public IValidator getResourceValidator() {
            // When https://review.source.android.com/#change,20168 is integrated,
            // change this to
            //return ResourceNameValidator.create(false, mEditor.getProject(), ResourceType.ID);
            return null;
        }

        public String displayReferenceInput(String currentValue) {
            AndroidXmlEditor editor = mEditor.getLayoutEditor();
            IProject project = editor.getProject();
            if (project != null) {
                // get the resource repository for this project and the system resources.
                ResourceRepository projectRepository =
                    ResourceManager.getInstance().getProjectResources(project);
                Shell shell = AdtPlugin.getDisplay().getActiveShell();
                if (shell == null) {
                    return null;
                }
                ReferenceChooserDialog dlg = new ReferenceChooserDialog(
                        project,
                        projectRepository,
                        shell);

                dlg.setCurrentResource(currentValue);

                if (dlg.open() == Window.OK) {
                    return dlg.getCurrentResource();
                }
            }

            return null;
        }

        public String displayResourceInput(String resourceTypeName, String currentValue) {
            return displayResourceInput(resourceTypeName, currentValue, null);
        }

        private String displayResourceInput(String resourceTypeName, String currentValue,
                IInputValidator validator) {
            AndroidXmlEditor editor = mEditor.getLayoutEditor();
            IProject project = editor.getProject();
            ResourceType type = ResourceType.getEnum(resourceTypeName);
            if (project != null) {
                // get the resource repository for this project and the system resources.
                ResourceRepository projectRepository = ResourceManager.getInstance()
                        .getProjectResources(project);
                Shell shell = AdtPlugin.getDisplay().getActiveShell();
                if (shell == null) {
                    return null;
                }

                AndroidTargetData data = editor.getTargetData();
                ResourceRepository systemRepository = data.getFrameworkResources();

                // open a resource chooser dialog for specified resource type.
                ResourceChooser dlg = new ResourceChooser(project, type, projectRepository,
                        systemRepository, shell);

                if (validator != null) {
                    // Ensure wide enough to accommodate validator error message
                    dlg.setSize(70, 10);
                    dlg.setInputValidator(validator);
                }

                dlg.setCurrentResource(currentValue);

                int result = dlg.open();
                if (result == ResourceChooser.CLEAR_RETURN_CODE) {
                    return ""; //$NON-NLS-1$
                } else if (result == Window.OK) {
                    return dlg.getCurrentResource();
                }
            }

            return null;
        }

        public String[] displayMarginInput(String all, String left, String right, String top,
                String bottom) {
            AndroidXmlEditor editor = mEditor.getLayoutEditor();
            IProject project = editor.getProject();
            if (project != null) {
                Shell shell = AdtPlugin.getDisplay().getActiveShell();
                if (shell == null) {
                    return null;
                }
                AndroidTargetData data = editor.getTargetData();
                MarginChooser dialog = new MarginChooser(shell, project, data, all, left, right,
                        top, bottom);
                if (dialog.open() == Window.OK) {
                    return dialog.getMargins();
                }
            }

            return null;
        }

        public String displayIncludeSourceInput() {
            AndroidXmlEditor editor = mEditor.getLayoutEditor();
            IInputValidator validator = CyclicDependencyValidator.create(editor.getInputFile());
            return displayResourceInput(ResourceType.LAYOUT.getName(), null, validator);
        }

        public void select(final Collection<INode> nodes) {
            LayoutCanvas layoutCanvas = mEditor.getCanvasControl();
            final SelectionManager selectionManager = layoutCanvas.getSelectionManager();
            selectionManager.select(nodes);
            // ALSO run an async select since immediately after nodes are created they
            // may not be selectable. We can't ONLY run an async exec since
            // code may depend on operating on the selection.
            layoutCanvas.getDisplay().asyncExec(new Runnable() {
                public void run() {
                    selectionManager.select(nodes);
                }
            });
        }

        public String displayFragmentSourceInput() {
            try {
                // Compute a search scope: We need to merge all the subclasses
                // android.app.Fragment and android.support.v4.app.Fragment
                IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
                IJavaProject javaProject = BaseProjectHelper.getJavaProject(mProject);
                if (javaProject != null) {
                    IType oldFragmentType = javaProject.findType(CLASS_V4_FRAGMENT);

                    // First check to make sure fragments are available, and if not,
                    // warn the user.
                    IAndroidTarget target = Sdk.getCurrent().getTarget(mProject);
                    if (target.getVersion().getApiLevel() < 11 && oldFragmentType == null) {
                        // Compatibility library must be present
                        Status status = new Status(IStatus.WARNING, AdtPlugin.PLUGIN_ID, 0,
                            "Fragments require API level 11 or higher, or a compatibility "
                                    + "library for older versions.\n\n"
                                    + "Please install the \"Android Compatibility Package\" from "
                                    + "the SDK manager and add its .jar file "
                                    + "(extras/android/compatibility/v4/android-support-v4.jar) "
                                    + "to the project's "
                                    + " Java Build Path.", null);
                        ErrorDialog.openError(Display.getCurrent().getActiveShell(),
                                "Fragment Warning", null, status);

                        // TODO: Offer to automatically perform configuration for the user;
                        // either upgrade project to require API 11, or first install the
                        // compatibility library via the SDK manager and then adding
                        // ${SDK_HOME}/extras/android/compatibility/v4/android-support-v4.jar
                        // to the project jar dependencies.
                        return null;
                    }

                    // Look up sub-types of each (new fragment class and compatibility fragment
                    // class, if any) and merge the two arrays - then create a scope from these
                    // elements.
                    IType[] fragmentTypes = new IType[0];
                    IType[] oldFragmentTypes = new IType[0];
                    if (oldFragmentType != null) {
                        ITypeHierarchy hierarchy =
                            oldFragmentType.newTypeHierarchy(new NullProgressMonitor());
                        oldFragmentTypes = hierarchy.getAllSubtypes(oldFragmentType);
                    }
                    IType fragmentType = javaProject.findType(CLASS_FRAGMENT);
                    if (fragmentType != null) {
                        ITypeHierarchy hierarchy =
                            fragmentType.newTypeHierarchy(new NullProgressMonitor());
                        fragmentTypes = hierarchy.getAllSubtypes(fragmentType);
                    }
                    IType[] subTypes = new IType[fragmentTypes.length + oldFragmentTypes.length];
                    System.arraycopy(fragmentTypes, 0, subTypes, 0, fragmentTypes.length);
                    System.arraycopy(oldFragmentTypes, 0, subTypes, fragmentTypes.length,
                            oldFragmentTypes.length);
                    scope = SearchEngine.createJavaSearchScope(subTypes, IJavaSearchScope.SOURCES);
                }

                Shell parent = AdtPlugin.getDisplay().getActiveShell();
                SelectionDialog dialog = JavaUI.createTypeDialog(
                        parent,
                        new ProgressMonitorDialog(parent),
                        scope,
                        IJavaElementSearchConstants.CONSIDER_CLASSES, false,
                        // Use ? as a default filter to fill dialog with matches
                        "?", //$NON-NLS-1$
                        new TypeSelectionExtension() {
                            @Override
                            public ITypeInfoFilterExtension getFilterExtension() {
                                return new ITypeInfoFilterExtension() {
                                    public boolean select(ITypeInfoRequestor typeInfoRequestor) {
                                        int modifiers = typeInfoRequestor.getModifiers();
                                        if (!Flags.isPublic(modifiers)
                                                || Flags.isInterface(modifiers)
                                                || Flags.isEnum(modifiers)) {
                                            return false;
                                        }
                                        return true;
                                    }
                                };
                            }
                        });

                dialog.setTitle("Choose Fragment Class");
                dialog.setMessage("Select a Fragment class (? = any character, * = any string):");
                if (dialog.open() == IDialogConstants.CANCEL_ID) {
                    return null;
                }

                Object[] types = dialog.getResult();
                if (types != null && types.length > 0) {
                    return ((IType) types[0]).getFullyQualifiedName();
                }
            } catch (JavaModelException e) {
                AdtPlugin.log(e, null);
            } catch (CoreException e) {
                AdtPlugin.log(e, null);
            }
            return null;
        }
    }
}
