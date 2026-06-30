package io.runtimeconditions.profiler.profile;

import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

final class SemanticModel extends TreePathScanner<Void, Void> {
    private final Trees trees;
    private final Map<Tree, Element> elements = new IdentityHashMap<>();
    private final Map<Tree, TypeMirror> types = new IdentityHashMap<>();

    private SemanticModel(Trees trees) {
        this.trees = trees;
    }

    static SemanticModel index(JavacTask task, List<CompilationUnitTree> units) {
        SemanticModel model = new SemanticModel(Trees.instance(task));
        for (CompilationUnitTree unit : units) {
            model.scan(unit, null);
        }
        return model;
    }

    Element element(Tree tree) {
        return elements.get(tree);
    }

    TypeMirror type(Tree tree) {
        return types.get(tree);
    }

    String constantString(Tree tree) {
        Element element = element(tree);
        if (element instanceof VariableElement variable) {
            Object value = variable.getConstantValue();
            if (value instanceof String stringValue) {
                return stringValue;
            }
        }
        return null;
    }

    String bindingConstantName(Tree tree) {
        Element element = element(tree);
        if (!(element instanceof VariableElement variable)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add(variable.getSimpleName().toString());
        Element owner = variable.getEnclosingElement();
        while (owner instanceof TypeElement typeElement) {
            parts.add(0, typeElement.getSimpleName().toString());
            owner = typeElement.getEnclosingElement();
        }
        return String.join(".", parts);
    }

    @Override
    public Void visitClass(ClassTree node, Void unused) {
        recordCurrent(node);
        return super.visitClass(node, unused);
    }

    @Override
    public Void visitVariable(VariableTree node, Void unused) {
        recordCurrent(node);
        recordChild(node.getType());
        recordChild(node.getInitializer());
        return super.visitVariable(node, unused);
    }

    @Override
    public Void visitIdentifier(IdentifierTree node, Void unused) {
        recordCurrent(node);
        return super.visitIdentifier(node, unused);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
        recordCurrent(node);
        return super.visitMemberSelect(node, unused);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
        recordCurrent(node);
        recordChild(node.getMethodSelect());
        return super.visitMethodInvocation(node, unused);
    }

    @Override
    public Void visitParameterizedType(ParameterizedTypeTree node, Void unused) {
        recordCurrent(node);
        return super.visitParameterizedType(node, unused);
    }

    @Override
    public Void visitArrayType(ArrayTypeTree node, Void unused) {
        recordCurrent(node);
        return super.visitArrayType(node, unused);
    }

    private void recordCurrent(Tree tree) {
        TreePath path = getCurrentPath();
        if (path != null) {
            record(path, tree);
        }
    }

    private void recordChild(Tree tree) {
        if (tree == null || getCurrentPath() == null) {
            return;
        }
        record(new TreePath(getCurrentPath(), tree), tree);
    }

    private void record(TreePath path, Tree tree) {
        try {
            Element element = trees.getElement(path);
            if (element != null) {
                elements.put(tree, element);
            }
        } catch (RuntimeException ignored) {
        }
        try {
            TypeMirror type = trees.getTypeMirror(path);
            if (type != null) {
                types.put(tree, type);
            }
        } catch (RuntimeException ignored) {
        }
    }
}
