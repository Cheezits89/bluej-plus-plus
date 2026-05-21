/*
 This file is part of the BlueJ program.
 Copyright (C) 2024  Michael Kolling and John Rosenberg

 This program is free software; you can redistribute it and/or
 modify it under the terms of the GNU General Public License
 as published by the Free Software Foundation; either version 2
 of the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software
 Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.

 This file is subject to the Classpath exception as provided in the
 LICENSE.txt file that accompanied this code.
 */
package bluej.editor.flow;

import bluej.parser.nodes.NodeTree.NodeAndPosition;
import bluej.parser.nodes.ParsedNode;
import bluej.parser.nodes.*;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.Control;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import threadchecker.OnThread;
import threadchecker.Tag;

import bluej.parser.entity.JavaEntity;

import java.util.Iterator;
import java.util.List;

/**
 * A sidebar panel showing the structural outline of the current Java source file:
 * classes, methods, and fields. Clicking an entry moves the caret to that symbol.
 */
@OnThread(Tag.FXPlatform)
public class CodeOutlinePanel extends VBox
{
    private final TreeView<OutlineItem> treeView;
    private NavigationCallback navCallback;

    public CodeOutlinePanel()
    {
        getStyleClass().add("code-outline-panel");
        setPrefWidth(300);
        setMinWidth(120);
        setMaxWidth(Double.MAX_VALUE);
        setFillWidth(true);

        Label header = new Label("Outline");
        header.getStyleClass().add("code-outline-header");

        treeView = new TreeView<>();
        treeView.setShowRoot(false);
        treeView.setRoot(new TreeItem<>());
        treeView.getStyleClass().add("code-outline-tree");
        VBox.setVgrow(treeView, Priority.ALWAYS);

        treeView.setCellFactory(tv -> new TreeCell<OutlineItem>() {
            @Override
            @OnThread(value = Tag.FXPlatform, ignoreParent = true)
            protected void updateItem(OutlineItem item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.name());

                    // --- FIX: Generate a fresh icon view instance for this cell row ---
                    setGraphic(iconForType(item.nodeType()));

                    // Force calculations to stay active on layout updates
                    setPrefWidth(Control.USE_COMPUTED_SIZE);
                    setMinWidth(Control.USE_COMPUTED_SIZE);
                    setMaxWidth(Double.MAX_VALUE);
                }
            }
        });

        treeView.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<TreeItem<OutlineItem>>()
        {
            @Override
            @OnThread(value = Tag.FXPlatform, ignoreParent = true)
            public void changed(ObservableValue<? extends TreeItem<OutlineItem>> obs,
                                TreeItem<OutlineItem> old, TreeItem<OutlineItem> selected)
            {
                if (selected != null && navCallback != null)
                {
                    navCallback.navigateTo(selected.getValue().position());
                }
            }
        });

        getChildren().addAll(header, treeView);
    }

    /** Rebuilds the outline tree from the given parse root. Pass null to clear. */
    public void refresh(ParsedNode rootNode)
    {
        TreeItem<OutlineItem> root = new TreeItem<>();
        if (rootNode != null)
        {
            walkNode(rootNode, 0, root);
        }
        treeView.setRoot(root);
        root.setExpanded(true);
        root.getChildren().forEach(c -> c.setExpanded(true));
    }

    public void setNavigationCallback(NavigationCallback cb)
    {
        this.navCallback = cb;
    }

    private void walkNode(ParsedNode node, int nodePosition, TreeItem<OutlineItem> parent)
    {
        Iterator<NodeAndPosition<ParsedNode>> children = node.getChildren(nodePosition);
        while (children.hasNext())
        {
            NodeAndPosition<ParsedNode> nap = children.next();
            ParsedNode child = nap.getNode();
            int type = child.getNodeType();

            if (type == ParsedNode.NODETYPE_TYPEDEF
                    || type == ParsedNode.NODETYPE_METHODDEF
                    || type == ParsedNode.NODETYPE_FIELD)
            {
                String name = child.getName() + extensionForType(type, nap.getNode());
                if (name != null && !name.isEmpty())
                {
                    TreeItem<OutlineItem> item = new TreeItem<>(
                            new OutlineItem(name, type, nap.getPosition())
                    );
                    // --- FIX: Removed item.setGraphic(iconForType(type)); from here ---
                    parent.getChildren().add(item);
                    // recurse into class/interface bodies only, not method bodies
                    if (type == ParsedNode.NODETYPE_TYPEDEF)
                    {
                        walkNode(child, nap.getPosition(), item);
                    }
                }
            }
            else
            {
                walkNode(child, nap.getPosition(), parent);
            }
        }
    }

    private Label iconForType(int type)
    {
        Label icon = new Label();
        switch (type)
        {
            case ParsedNode.NODETYPE_TYPEDEF   -> { icon.setText("C"); icon.getStyleClass().add("outline-icon-class"); }
            case ParsedNode.NODETYPE_METHODDEF -> { icon.setText("M"); icon.getStyleClass().add("outline-icon-method"); }
            case ParsedNode.NODETYPE_FIELD     -> { icon.setText("F"); icon.getStyleClass().add("outline-icon-field"); }
        }
        icon.getStyleClass().add("outline-icon");
        return icon;
    }

    private String extensionForType(int type, ParsedNode node) {
        String result = "";
        switch(type) {
            case ParsedNode.NODETYPE_TYPEDEF   -> { result = ((ParsedTypeNode) node).getPrefix(); }
            case ParsedNode.NODETYPE_METHODDEF -> {
                MethodNode mn = (MethodNode) node;
                List<String> paramNames = mn.getParamNames();
                List<JavaEntity> paramTypes = mn.getParamTypes();
                String params = "";
                for (int i = 0; i < paramNames.size(); i++) {
                    params += paramTypes.get(i).getName() + " " + paramNames.get(i);
                }

                result = "(" + params + ")" + " -> " + (((MethodNode) node).getReturnType() != null ? ((MethodNode) node).getReturnType().getName() : null);
            }
            case ParsedNode.NODETYPE_FIELD     -> { result = ": " + ((FieldNode) node).getFieldTypeAsPlainString(); }
        }

        return result;
    }

    public record OutlineItem(String name, int nodeType, int position)
    {
        @Override
        @OnThread(Tag.Any)
        public String toString()
        {
            return name;
        }
    }

    @FunctionalInterface
    public interface NavigationCallback
    {
        void navigateTo(int documentOffset);
    }
}
