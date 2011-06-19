package org.jetbrains.plugins.groovy.spoc;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.GrModifierList;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.annotation.GrAnnotation;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.annotation.GrAnnotationArgumentList;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.annotation.GrAnnotationNameValuePair;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyPsiManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Sergey Evdokimov
 */
public class SporUnrollReferenceProvider extends PsiReferenceProvider {

  private static final Pattern PATTERN = Pattern.compile("\\#([\\w_]+)");

  @NotNull
  @Override
  public PsiReference[] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
    GrAnnotationNameValuePair nvp = (GrAnnotationNameValuePair)element.getParent();

    String name = nvp.getName();
    if (name != null && !name.equals("value")) return PsiReference.EMPTY_ARRAY;

    PsiElement argumentList = nvp.getParent();
    if (!(argumentList instanceof GrAnnotationArgumentList)) return PsiReference.EMPTY_ARRAY;

    PsiElement eAnnotation = argumentList.getParent();
    if (!(eAnnotation instanceof GrAnnotation)) return PsiReference.EMPTY_ARRAY;

    GrAnnotation annotation = (GrAnnotation)eAnnotation;

    String shortName = annotation.getShortName();
    if (!shortName.equals("Unroll") && !shortName.equals("spock.lang.Unroll")) return PsiReference.EMPTY_ARRAY;

    PsiClass aClass = PsiTreeUtil.getParentOfType(annotation, PsiClass.class);
    if (aClass == null) return PsiReference.EMPTY_ARRAY;

    if (!GroovyPsiManager.isInheritorCached(aClass, SpocUtils.SPEC_CLASS_NAME)) return PsiReference.EMPTY_ARRAY;

    PsiElement modifierList = annotation.getParent();
    if (!(modifierList instanceof GrModifierList)) return PsiReference.EMPTY_ARRAY;

    PsiElement eMethod = modifierList.getParent();
    if (!(eMethod instanceof GrMethod)) return PsiReference.EMPTY_ARRAY;

    final GrMethod method = (GrMethod)eMethod;

    ElementManipulator<PsiElement> manipulator = ElementManipulators.getManipulator(element);
    TextRange rangeInElement = manipulator.getRangeInElement(element);

    String text = rangeInElement.substring(element.getText());

    final List<SpockVariableReference> references = new ArrayList<SpockVariableReference>();

    Matcher matcher = PATTERN.matcher(text);
    while (matcher.find()) {
      TextRange range = new TextRange(rangeInElement.getStartOffset() + matcher.start(1), rangeInElement.getStartOffset() + matcher.end(1));

      references.add(new SpockVariableReference(element, range, references, method));
    }

    return references.toArray(new PsiReference[references.size()]);
  }

  private static class SpockVariableReference extends PsiReferenceBase<PsiElement> {

    private final PsiElement myLeafElement;
    private final List<SpockVariableReference> myReferences;
    private final GrMethod myMethod;

    public SpockVariableReference(PsiElement element, TextRange range, List<SpockVariableReference> references, GrMethod method) {
      super(element, range);
      myReferences = references;
      myMethod = method;
      myLeafElement = element.getFirstChild();
    }

    @Override
    public PsiElement resolve() {
      Map<String, SpocVariableDescriptor> variableMap = SpocUtils.getVariableMap(myMethod);
      String value = getValue();
      SpocVariableDescriptor descriptor = variableMap.get(value);
      if (descriptor == null) return null;
      return descriptor.getVariable();
    }

    @NotNull
    @Override
    public Object[] getVariants() {
      Map<String, SpocVariableDescriptor> variableMap = SpocUtils.getVariableMap(myMethod);

      Object[] res = new Object[variableMap.size()];

      int i = 0;
      for (SpocVariableDescriptor descriptor : variableMap.values()) {
        res[i++] = descriptor.getVariable();
      }

      return res;
    }

    @Override
    public PsiElement handleElementRename(String newElementName) throws IncorrectOperationException {
      if (getElement().getFirstChild() != myLeafElement) { // Element already renamed.
        return getElement();
      }

      String oldValue = getValue();

      PsiElement res = null;

      for (int i = myReferences.size(); --i >= 0; ) {
        SpockVariableReference reference = myReferences.get(i);

        if (oldValue.equals(reference.getCanonicalText())) {
          res = reference.superHandleRename(newElementName);
        }
      }

      return res;
    }

    public PsiElement superHandleRename(String newName) {
      return super.handleElementRename(newName);
    }
  }
}
