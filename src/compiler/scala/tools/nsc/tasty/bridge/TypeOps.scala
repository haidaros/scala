package scala.tools.nsc.tasty.bridge

import scala.tools.nsc.tasty.TastyFlags.TastyFlagSet
import scala.tools.nsc.tasty.TastyUniverse
import scala.tools.nsc.tasty.Names.TastyName
import scala.tools.nsc.tasty.Names.TastyName.QualifiedName
import scala.tools.nsc.tasty.Names.TastyName.SimpleName

import scala.tools.nsc.tasty._
import scala.tools.nsc.tasty.Names.TastyName.ModuleName
import scala.tools.nsc.tasty.Names.TastyName.SignedName

trait TypeOps extends TastyKernel { self: TastyUniverse =>
  import Contexts._
  import FlagSets._

  def isTastyLazyType(rawInfo: Type): Boolean = rawInfo.isInstanceOf[TastyLazyType]

  def erasedNameToErasedType(name: TastyName)(implicit ctx: Context): Type = {
    def specialised(terminal: SimpleName) = terminal.raw match {
      case s"$raw[]" => (true, SimpleName(raw))
      case _         => (false, terminal)
    }
    def erasedType(isArray: Boolean, isModule: Boolean, erasedName: TastyName) = {
      val termName = mkTermName(erasedName.source)
      val sym = {
        if (isModule) {
          ctx.loadingMirror.getModuleIfDefined(termName)
        }
        else {
          ctx.loadingMirror.getClassIfDefined(termName.toTypeName)
        }
      }
      assert(sym !== noSymbol, s"could not find ${if (isModule) "object" else "class"} for $termName")
      val tpe0 = sym.tpe.erasure
      if (isArray) defn.arrayType(tpe0) else tpe0
    }
    (name.stripModulePart: @unchecked) match {
      case terminal: SimpleName => // unqualified in the <empty> package
        val (isArray, sel) = specialised(terminal)
        erasedType(isArray, name.isModuleName, sel)
      case QualifiedName(path, TastyName.PathSep, terminal) =>
        val (isArray, sel) = specialised(terminal)
        erasedType(isArray, name.isModuleName, QualifiedName(path, TastyName.PathSep, sel))
    }
  }

  object TypeOps {
    implicit final class StripOps(tpe: Type) {
      def stripLowerBoundsIfPoly: Type = tpe match {
        case symbolTable.TypeBounds(_, hi: PolyType) => hi
        case tpe => tpe
      }
    }
  }

  case object AndType extends Type

  /**
   * Ported from dotc
   */
  abstract class TastyLazyType extends LazyType with FlagAgnosticCompleter { self =>
    private[this] val NoSymbolFn = (_: Context) => noSymbol
    private[this] var myDecls: Scope = emptyScope
    private[this] var mySourceModuleFn: Context => Symbol = NoSymbolFn
    private[this] var myModuleClassFn: Context => Symbol = NoSymbolFn
    private[this] var myTastyFlagSet: TastyFlagSet = emptyTastyFlags

    /** The type parameters computed by the completer before completion has finished */
    def completerTypeParams(sym: Symbol)(implicit ctx: Context): List[Symbol] = sym.info.typeParams
    //      if (sym.is(Touched)) Nil // return `Nil` instead of throwing a cyclic reference
    //      else sym.info.typeParams

    override def decls: Scope = myDecls
    def sourceModule(implicit ctx: Context): Symbol = mySourceModuleFn(ctx)
    def moduleClass(implicit ctx: Context): Symbol = myModuleClassFn(ctx)
    def tastyFlagSet: TastyFlagSet = myTastyFlagSet

    def withDecls(decls: Scope): this.type = { myDecls = decls; this }
    def withSourceModule(sourceModuleFn: Context => Symbol): this.type = { mySourceModuleFn = sourceModuleFn; this }
    def withModuleClass(moduleClassFn: Context => Symbol): this.type = { myModuleClassFn = moduleClassFn; this }
    def withTastyFlagSet(flags: TastyFlagSet): this.type = { myTastyFlagSet = flags; this }

    override def load(sym: Symbol): Unit = complete(sym)
  }

  object NamedType {
    import SymbolOps._
    def apply(prefix: Type, designator: Symbol): Type =
      if (designator.isType
          // With this second constraint, we avoid making singleton types for
          // static forwarders to modules (or you get a stack overflow trying to get sealedDescendents in patmat)
          // [what do we do about Scala 3 enum constants?]
          || designator.is(Method | JavaStatic)) {
        mkTypeRef(prefix, designator, Nil)
      } else {
        mkSingleType(prefix, designator)
      }
  }

  private def selectSymFromSig0(qualType: Type, name: Name, sig: Signature[Type])(implicit ctx: Context): Either[String,(Int, Symbol)] =
    selectSymFromSig(qualType, name, sig).toRight(s"No matching overload of $qualType.$name with signature ${sig.show}")

  private def reportThenErrorTpe(msg: String): Type = {
    reporter.error(noPosition, msg)
    errorType
  }

  def mkTypeRef(tpe: Type, name: TastyName, selectingTerm: Boolean)(implicit ctx: Context): Type = {
    import NameOps._
    val encoded  = name.toEncodedTermName
    val selector = if (selectingTerm) encoded else encoded.toTypeName
    def debugSelectedSym(sym: Symbol): Symbol = {
      ctx.log(s"selected ${showSym(sym)} : ${sym.tpe}")
      sym
    }
    val resolved = name match {
      case SignedName(qual, sig) =>
        selectSymFromSig0(tpe, selector, sig.map(erasedNameToErasedType)).map(pair => debugSelectedSym(pair._2))
      case _ => Right(tpe.member(selector))
    }
    val tpeOrErr = resolved.map(sym => NamedType(tpe, if (name.isModuleName) sym.linkedClassOfClass else sym))
    tpeOrErr.fold(reportThenErrorTpe, identity)
  }

  def selectFromSig(qualType: Type, name: Name, sig: Signature[Type])(implicit ctx: Context): Type = {
    val tpeOrErr = selectSymFromSig0(qualType, name, sig).map {
      case (tyParamCount, sym) =>
        var tpe = sym.tpe
        if (name === nme.CONSTRUCTOR && tyParamCount > 0) tpe = mkPolyType(sym.owner.typeParams, tpe)
        ctx.log(s"selected ${showSym(sym)} : $tpe")
        tpe
    }
    tpeOrErr.fold(reportThenErrorTpe, identity)
  }

  abstract class LambdaTypeCompanion[N <: Name, PInfo <: Type, LT <: LambdaType] {
    def apply(paramNames: List[N])(paramInfosExp: LT => List[PInfo], resultTypeExp: LT => Type): LT
  }

  object TypeParamLambda {
    def apply(typeParams: List[Symbol], ret: Type): LambdaType = new TypeParamLambda(typeParams, ret)
  }

  final class TypeParamLambda(override val typeParams: List[Symbol], val resType: Type) extends LambdaType {
    type ThisName = TypeName
    type PInfo    = TypeBounds

    val paramNames: List[TypeName]   = typeParams.map(_.name.toTypeName)
    val paramInfos: List[TypeBounds] = typeParams.map(_.tpe.bounds)

    validateThisLambda()

    override val productPrefix                = "TypeParamLambda"
    override def canEqual(that: Any): Boolean = that.isInstanceOf[TypeParamLambda]
  }

  abstract class LambdaType extends Type with Product { lambdaTpe =>
    type ThisName <: Name
    type PInfo <: Type

    val paramNames: List[ThisName]
    val paramInfos: List[PInfo]
    val resType: Type

    private[this] var myParamRefs: List[TypeParamRef] = _

    final def paramRefs: List[TypeParamRef] = {
      if (myParamRefs `eq` null) myParamRefs = paramNames.indices.toList.map(i => new TypeParamRef(this, i))
      myParamRefs
    }

    override final def safeToString: String = {
      val args = paramNames.zip(paramInfos).map {
        case (name, info) => s"${name}$info"
      }.mkString("[", ", ", "]")
      s"$args =>> $resType"
    }

    def typeParams: List[Symbol] // deferred to final implementation

    final protected def validateThisLambda(): Unit = {
      assert(resType.isComplete, this)
      assert(paramNames.nonEmpty, this)
      assert(paramInfos.length == paramNames.length, this)
    }

    /**Best effort to transform this to an equivalent canonical representation in scalac.
     */
    final def canonicalForm: Type = {
      val resUpper = resType.upperBound
      val resLower = if (resType `eq` resType.bounds) resType.lowerBound else defn.NothingTpe
      if (resUpper.typeArgs.nonEmpty && resUpper.typeArgs == paramInfos) {
        val resUpperRef = resUpper.asInstanceOf[TypeRef]
        mkPolyType(
          typeParams,
          mkTypeBounds(
            resLower,
            mkExistentialType(
              typeParams,
              mkTypeRef(resUpperRef.pre, resUpperRef.sym, typeParams.map(_.tpe))
            )
          )
        )
      }
      else if (resUpper.typeArgs.isEmpty) {
        mkPolyType(typeParams, mkTypeBounds(resLower, resUpper))
      }
      else if (resUpper.typeArgs == paramRefs) {
        resUpper.typeConstructor
      }
      else {
        mkPolyType(typeParams, resUpper)
      }
    }

    final def productArity: Int = 2
    final def productElement(n: Int): Any = n match {
      case 0 => paramNames
      case 1 => resType
      case _ => throw new IndexOutOfBoundsException(n.toString)
    }
    def canEqual(that: Any): Boolean = that.isInstanceOf[LambdaType]
    override final def equals(that: Any): Boolean = that match {
      case lambdaType: LambdaType =>
        (lambdaType.canEqual(this)
          && lambdaType.paramNames == paramNames
          && lambdaType.resType == resType)
      case _ => false
    }
  }

  final class TypeParamRef(binder: LambdaType, i: Int) extends Type with Product {

    override def safeToString(): String = binder.paramNames(i).toString()

    override val productPrefix: String = "TypeParamRef"
    val productArity = 1
    def productElement(n: Int): Any = n match {
      case 0 => binder.paramNames(i)
      case _ => throw new IndexOutOfBoundsException(n.toString)
    }
    def canEqual(that: Any): Boolean = that.isInstanceOf[TypeParamRef]
  }

  object HKTypeLambda extends LambdaTypeCompanion[TypeName, TypeBounds, HKTypeLambda] {
    def apply(paramNames: List[TypeName])(
        paramInfosExp: HKTypeLambda => List[TypeBounds], resultTypeExp: HKTypeLambda => Type): HKTypeLambda =
      new HKTypeLambda(paramNames)(paramInfosExp, resultTypeExp)
  }

  final class HKTypeLambda(val paramNames: List[TypeName])(
      paramInfosExp: HKTypeLambda => List[TypeBounds], resultTypeExp: HKTypeLambda => Type)
  extends LambdaType {
    type ThisName = TypeName
    type PInfo = TypeBounds

    private[this] var myTypeParams: List[Symbol] = _

    override val productPrefix       = "HKTypeLambda"
    val paramInfos: List[TypeBounds] = paramInfosExp(this)
    val resType: Type                = resultTypeExp(this)

    validateThisLambda()

    override def typeParams: List[Symbol] = {
      if (myTypeParams `eq` null) myTypeParams = paramNames.zip(paramInfos).map {
        case (name, info) => mkNewFreeTypeSymbol(name.toTypeName, Param | Deferred, name.toString).setInfo(info)
      }
      myTypeParams
    }

    override def canEqual(that: Any): Boolean = that.isInstanceOf[HKTypeLambda]
  }

  def showRaw(tpe: Type): String = symbolTable.showRaw(tpe)
}