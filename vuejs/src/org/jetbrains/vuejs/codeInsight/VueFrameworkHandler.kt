package org.jetbrains.vuejs.codeInsight

import com.intellij.lang.ASTNode
import com.intellij.lang.ecmascript6.psi.JSExportAssignment
import com.intellij.lang.javascript.JSElementTypes
import com.intellij.lang.javascript.JSStubElementTypes
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.index.FrameworkIndexingHandler
import com.intellij.lang.javascript.index.JSSymbolUtil
import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.impl.JSPsiImplUtils
import com.intellij.lang.javascript.psi.resolve.JSTypeEvaluator
import com.intellij.lang.javascript.psi.stubs.JSElementIndexingData
import com.intellij.lang.javascript.psi.stubs.JSImplicitElementStructure
import com.intellij.lang.javascript.psi.stubs.impl.JSElementIndexingDataImpl
import com.intellij.lang.javascript.psi.stubs.impl.JSImplicitElementImpl
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.XmlRecursiveElementWalkingVisitor
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.xml.util.HtmlUtil
import org.jetbrains.vuejs.MIXINS
import org.jetbrains.vuejs.VueFileType
import org.jetbrains.vuejs.index.*
import org.jetbrains.vuejs.language.VueJSLanguage
import org.jetbrains.vuejs.language.VueVForExpression

class VueFrameworkHandler : FrameworkIndexingHandler() {
  init {
    INDICES.values.forEach { JSImplicitElementImpl.ourUserStringsRegistry.registerUserString(it) }
  }

  companion object {
    const val VUE = "Vue"
    private val VUE_DESCRIPTOR_OWNERS = arrayOf(VUE, "mixin", "component", "extends")
  }

  override fun findModule(result: PsiElement): PsiElement? = org.jetbrains.vuejs.codeInsight.findModule(result)

  override fun processAnyProperty(property: JSProperty, outData: JSElementIndexingData?): JSElementIndexingData? {
    val obj = property.parent as JSObjectLiteralExpression
    val firstProperty = obj.firstProperty
    if (firstProperty == null) return outData

    val out = outData ?: JSElementIndexingDataImpl()
    if (MIXINS == property.name && property.value is JSArrayLiteralExpression) {
      (property.value as JSArrayLiteralExpression).expressions
        .forEach {
          if (it is JSReferenceExpression) {
            out.addImplicitElement(JSImplicitElementImpl.Builder(LOCAL, property)
                                     .setUserString(VueMixinBindingIndex.JS_KEY)
                                     .setTypeString(it.text)
                                     .toImplicitElement())
          } else if (it is JSObjectLiteralExpression && it.firstProperty != null) {
            out.addImplicitElement(JSImplicitElementImpl.Builder(LOCAL, it.firstProperty!!)
                                     .setUserString(VueMixinBindingIndex.JS_KEY)
                                     .toImplicitElement())
          }
        }
    }

    if (obj.parent is JSExportAssignment && firstProperty == property && obj.containingFile.fileType == VueFileType.INSTANCE)  {
      tryProcessComponentInVue(obj, property, out)
    }

    if (firstProperty == property && ((obj.parent as? JSProperty) == null) && isDescriptorOfLinkedInstanceDefinition(obj)) {
      val binding = (obj.findProperty("el")?.value as? JSLiteralExpression)?.value as? String
      out.addImplicitElement(JSImplicitElementImpl.Builder(binding ?: "", property)
                               .setUserString(VueOptionsIndex.JS_KEY)
                               .toImplicitElement())
    }
    return if (out.isEmpty) outData else out
  }

  override fun shouldCreateStubForCallExpression(node: ASTNode?): Boolean {
    val reference = (node?.psi as? JSCallExpression)?.methodExpression as? JSReferenceExpression ?: return false
    return isVueComponentMethod(reference) || isVueMixinMethod(reference)
  }

  override fun processCallExpression(callExpression: JSCallExpression?, outData: JSElementIndexingData) {
    val reference = callExpression?.methodExpression as? JSReferenceExpression ?: return
    if (isVueComponentMethod(reference)) {
      val arguments = callExpression.arguments
      val componentName = getTextIfLiteral(arguments[0])
      if (arguments.size >= 2 && componentName != null) {
        val descriptor = arguments[1]
        val provider: PsiElement = (descriptor as? JSObjectLiteralExpression)?.firstProperty ?: callExpression
        // not null type string indicates the global component
        val typeString = (descriptor as? JSReferenceExpression)?.text ?: ""
        outData.addImplicitElement(JSImplicitElementImpl.Builder(componentName, provider)
                                     .setUserString(VueComponentsIndex.JS_KEY)
                                     .setTypeString(typeString)
                                     .toImplicitElement())
      }
    } else if (isVueMixinMethod(reference)) {
      val arguments = callExpression.arguments
      if (arguments.size == 1) {
        val provider = (arguments[0] as? JSObjectLiteralExpression)?.firstProperty ?: callExpression
        val typeString = (arguments[0] as? JSReferenceExpression)?.text ?: ""
        outData.addImplicitElement(JSImplicitElementImpl.Builder(GLOBAL, provider)
                                     .setUserString(VueMixinBindingIndex.JS_KEY)
                                     .setTypeString(typeString)
                                     .toImplicitElement())
      }
    }
  }

  private fun isVueComponentMethod(reference: JSReferenceExpression) =
    JSSymbolUtil.isAccurateReferenceExpressionName(reference, VUE, "component")

  private fun isVueMixinMethod(reference: JSReferenceExpression) =
    JSSymbolUtil.isAccurateReferenceExpressionName(reference, VUE, "mixin")

  private fun getTextIfLiteral(holder: PsiElement): String? {
    if (holder is JSLiteralExpression && holder.isQuotedLiteral) {
      return StringUtil.unquoteString(holder.text)
    }
    return null
  }

  private fun isDescriptorOfLinkedInstanceDefinition(obj: JSObjectLiteralExpression): Boolean {
    val argumentList = obj.parent as? JSArgumentList ?: return false
    if (argumentList.arguments[0] == obj) {
      return JSSymbolUtil.isAccurateReferenceExpressionName(
          (argumentList.parent as? JSNewExpression)?.methodExpression as? JSReferenceExpression, VUE) ||
        JSSymbolUtil.isAccurateReferenceExpressionName(
          (argumentList.parent as? JSCallExpression)?.methodExpression as? JSReferenceExpression, VUE, "extends")
    }
    return false
  }

  override fun shouldCreateStubForLiteral(node: ASTNode?): Boolean {
    if (node?.psi is JSLiteralExpression) {
      return hasSignificantValue(node.psi as JSLiteralExpression)
    }
    return super.shouldCreateStubForLiteral(node)
  }

  override fun hasSignificantValue(expression: JSLiteralExpression): Boolean {
    val parentType = expression.node.treeParent?.elementType ?: return false
    if (JSElementTypes.ARRAY_LITERAL_EXPRESSION == parentType ||
        JSElementTypes.PROPERTY == parentType && "required" == expression.node.treeParent.findChildByType(JSTokenTypes.IDENTIFIER)?.text) {
      return VueFileType.INSTANCE == expression.containingFile.fileType || insideVueDescriptor(expression)
    }
    return false
  }

  // limit building stub in other file types like js/html to Vue-descriptor-like members
  private fun insideVueDescriptor(expression: JSLiteralExpression): Boolean {
    val statement = TreeUtil.findParent(expression.node,
                                        TokenSet.create(JSStubElementTypes.CALL_EXPRESSION, JSStubElementTypes.NEW_EXPRESSION),
                                        TokenSet.create(JSElementTypes.EXPRESSION_STATEMENT)) ?: return false
    val ref = statement.findChildByType(JSElementTypes.REFERENCE_EXPRESSION) ?: return false
    return ref.getChildren(TokenSet.create(JSTokenTypes.IDENTIFIER)).filter { it.text in VUE_DESCRIPTOR_OWNERS }.any()
  }

  private fun tryProcessComponentInVue(obj: JSObjectLiteralExpression, property: JSProperty,
                                       outData: JSElementIndexingData) {
    val compName = (obj.findProperty("name")?.value as? JSLiteralExpression)?.value as? String
                   ?: FileUtil.getNameWithoutExtension(obj.containingFile.name)
    outData.addImplicitElement(JSImplicitElementImpl.Builder(compName, property).setUserString(VueComponentsIndex.JS_KEY).toImplicitElement())
  }

  override fun indexImplicitElement(element: JSImplicitElementStructure, sink: IndexSink?): Boolean {
    INDICES.filter { it.value == element.userString }.forEach { sink?.occurrence(it.key, element.name); return true }
    return false
  }

  override fun addTypeFromResolveResult(evaluator: JSTypeEvaluator?, result: PsiElement?, hasSomeType: Boolean): Boolean {
    if (result == null || evaluator == null || !org.jetbrains.vuejs.index.hasVue(result.project)) return false
    if (result is JSVariable && result.language is VueJSLanguage) {
      val vFor = result.parent as? VueVForExpression ?: result.parent.parent as? VueVForExpression
      val vForRef = vFor?.getReferenceExpression()
      val variables = vFor?.getVarStatement()?.variables
      if (vForRef != null && variables != null && !variables.isEmpty() && result == variables[0]) {
        if (JSPsiImplUtils.calculateTypeOfVariableForIteratedExpression(evaluator, vForRef, vFor)) return true
      }
    }
    return false
  }
}

fun findModule(result: PsiElement): PsiElement? {
  if (result is XmlFile) {
    if (result.fileType == VueFileType.INSTANCE) {
      val ref = Ref.create<JSElement>()
      result.accept(object : XmlRecursiveElementWalkingVisitor() {
        override fun visitXmlTag(tag: XmlTag?) {
          if (HtmlUtil.isScriptTag(tag)) {
            ref.set(PsiTreeUtil.findChildOfType(tag, JSElement::class.java))
            return
          }
        }
      })
      return ref.get()
    }
  }
  return null
}