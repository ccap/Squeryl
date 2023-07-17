/*******************************************************************************
 * Copyright 2010 Maxime Lévesque
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ***************************************************************************** */
package org.squeryl.internals
 

import java.lang.Class
import java.lang.annotation.Annotation
import java.lang.reflect.{Member, Constructor, Method, Field, Modifier}
import collection.mutable.{HashSet, ArrayBuffer}
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.matcher.ElementMatchers
import net.bytebuddy.implementation.MethodDelegation
import org.squeryl.dsl.ast.ViewExpressionNode
import org.squeryl.annotations._
import org.squeryl._
import dsl.CompositeKey
import scala.reflect.runtime._

class PosoMetaData[T: universe.TypeTag](val clasz: Class[T], val schema: Schema, val viewOrTable: View[T]) {

  private val scalaTpe: universe.Type = universe.typeOf[T]

  override def toString =
    'PosoMetaData + "[" + clasz.getSimpleName + "]" + fieldsMetaData.mkString("(",",",")")

  def findFieldMetaDataForProperty(name: String) =
     _fieldsMetaData.find(fmd => fmd.nameOfProperty == name)

  val isOptimistic = viewOrTable.ked.map(_.isOptimistic).getOrElse(false)

  val isPgOptimistic = classOf[PgOptimistic].isAssignableFrom(clasz)
  
  val constructor =
    _const.headOption.orElse(org.squeryl.internals.Utils.throwError(clasz.getName +
            " must have a 0 param constructor or a constructor with only primitive types")).get

  def fieldsMetaData =
    _fieldsMetaData.filter(! _.isTransient)
    
  /**
   * @arg fieldsMetaData the metadata of the persistent fields of this Poso
   * @arg primaryKey None if this Poso is not a KeyedEntity[], Either[a persistedField, a composite key]  
   */
  val (_fieldsMetaData, primaryKey): (Iterable[FieldMetaData], Option[Either[FieldMetaData,Method]]) = {
    val isImplicitMode = _isImplicitMode

    val setters = new ArrayBuffer[Method]

    val sampleInstance4OptionTypeDeduction =
      try {
        constructor._1.newInstance(constructor._2 :_*).asInstanceOf[AnyRef];
      }
      catch {
        case e:IllegalArgumentException =>
          throw new RuntimeException("invalid constructor choice " + constructor._1, e)
        case e:Exception =>
          throw new RuntimeException("exception occurred while invoking constructor : " + constructor._1, e)
      }
    
    val members = new ArrayBuffer[(Member,HashSet[Annotation])]

    _fillWithMembers(clasz, members)

    val name2MembersMap =
      members.groupBy(m => {

        val n = m._1.getName
        val idx = n.indexOf("_$eq")
        if(idx != -1)
          n.substring(0, idx)
        else
          n
      })

    val fmds = new ArrayBuffer[FieldMetaData];

    for(e <- name2MembersMap) {
      val name = e._1
      val v = e._2

      var a:Set[Annotation] = Set.empty
      for(memberWithAnnotationTuple <- v)
        a = a.union(memberWithAnnotationTuple._2)

      val members = v.map(t => t._1)

      // here we do a filter and not a find, because there can be more than one setter/getter/field
      // with the same name, we want one that is not an erased type, excluding return and input type
      // of java.lang.Object does it.

      val o = classOf[java.lang.Object]

      val field =
        members
          .collect { case f: Field => (f, effectiveFieldType(f)) }
          .filter { case (_, t) => t != o }
          .headOption

      val getter =
        members
          .collect { case m: Method if m.getName == name => (m, effectiveMethodType(m)) }
          .filter { case (_, t) => t != o }
          .headOption

      val setter =
        members
          .collect { case m: Method if m.getName.endsWith("_$eq") => (m, effectiveMethodType(m)) }
          .filter { case (_, t) => t != o }
          .headOption

      val property = (field, getter, setter, a)

      val groupProperty = (field.map(_._1), getter.map(_._1), setter.map(_._1), a)

      if(isImplicitMode && _groupOfMembersIsProperty(groupProperty)) {
        val isOptimisitcCounter =
          (for(k <- viewOrTable.ked; 
              counterProp <- k.optimisticCounterPropertyName if counterProp == name) yield true).isDefined
        try {
          val isPgOptimisticValue = isPgOptimistic && List("xmin", "ctid").contains(name)
          val r = FieldMetaData.factory.build(this, name, property, sampleInstance4OptionTypeDeduction, isOptimisitcCounter, isPgOptimisticValue)
          fmds.append(r)
        }
        catch {
          case e:Exception =>
            println(">>>" + clasz.getCanonicalName)
            println(">>>" + name)

            throw new RuntimeException(
              Utils.failSafeString(
              "error while reflecting on metadata for " + property + 
              " of class " + this.clasz.getCanonicalName), e)
        }
      }
    }

    var k = fmds.find(fmd => fmd.isIdFieldOfKeyedEntity)

    val compositePrimaryKeyGetter: Option[Method] =
      if(k != None) // can't have both PK Field and CompositePK
        None
      else {
        // verify if we have an 'id' method that is a composite key, in this case we need to construct a
        // FieldMetaData that will become the 'primaryKey' field of this PosoMetaData
        
        viewOrTable.ked.map { ked =>

          val pkMethod = clasz.getMethod(ked.idPropertyName)

          assert(pkMethod != null, "Could not get getter for " + ked.idPropertyName + " in " + clasz.getCanonicalName())

          pkMethod
        }
      }

    val metaDataForPk: Option[Either[FieldMetaData,Method]] =
      if(k != None)
        Some(Left(k.get))
      else if(compositePrimaryKeyGetter != None)
        Some(Right(compositePrimaryKeyGetter.get))
      else
        None
    
    (fmds, metaDataForPk) //: (Iterable[FieldMetaData], Option[Either[FieldMetaData,Method]])
  }

  def optimisticCounter =
    fieldsMetaData.find(fmd => fmd.isOptimisticCounter)

  def pgOptimisticValues =
    fieldsMetaData.filter(fmd => fmd.isPgOptimisticValue)

  def pgOptimisticDescr(o: AnyRef) =
    fieldsMetaData.filter(_.isPgOptimisticValue) map (f => f.nameOfProperty + " = " + f.getNativeJdbcValue(o)) mkString(", ")

  if(isOptimistic)
    assert(optimisticCounter != None)

  def _const = {

    val r = new ArrayBuffer[(Constructor[_],Array[Object])]

//    for(ct <- clasz.getConstructors)
//      println("CT: " + ct.getParameterTypes.map(c=>c.getName).mkString(","))
    
    for(ct <- clasz.getConstructors)
      _tryToCreateParamArray(r, ct)

    r.sortWith(
      (a:(Constructor[_],Array[Object]),
       b:(Constructor[_],Array[Object])) => a._2.length < b._2.length
    )
  }

  def _tryToCreateParamArray(
    r: ArrayBuffer[(Constructor[_],Array[Object])],
    c: Constructor[_]): Unit = {

    val params: Array[Class[_]] = c.getParameterTypes

    if(params.length >= 1) {
      val cn = clasz.getName
      val test = params(0).getName + "$" + clasz.getSimpleName
      if(cn == test)
        org.squeryl.internals.Utils.throwError("inner classes are not supported, except when outer class is a singleton (object)\ninner class is : " + cn)
    }

    def paramListsCompatible(javaParams: List[Class[_]], scalaParams: List[universe.Symbol]): Boolean =
      javaParams.zip(scalaParams).forall { case (cls, param) =>
        runtimeClass(param.typeSignature.erasure).exists(cls.isAssignableFrom)
      }

    val scalaConstructorParams = scalaTpe.decls
      .filter(_.isConstructor)
      .map(_.asTerm.typeSignature)
      .find(x => x.paramLists.length == 1 &&
        x.paramLists(0).length == params.length &&
        paramListsCompatible(params.toList, x.paramLists(0))
      )
      .getOrElse(Utils.throwError("Could not find Scala constructor corresponding to Java one"))
      .paramLists(0)
      .toVector

    val res = new Array[Object](params.size)

    for(i <- 0 to params.length -1) {
      val cl = findTaggedType(scalaConstructorParams(i).typeSignature).flatMap(runtimeClass).getOrElse(params(i))
      val isOption = scalaConstructorParams(i).typeSignature.typeSymbol == universe.typeOf[Option[_]].typeSymbol
      val v = FieldMetaData.createDefaultValue(schema.fieldMapper, c, isOption, cl, None)
      res(i) = v
    }

    r.append((c, res))
  }

  private def _noOptionalColumnDeclared =
    org.squeryl.internals.Utils.throwError("class " + clasz.getName + " has an Option[] member with no Column annotation with optionType declared.")

  //def createSamplePoso[T](vxn: ViewExpressionNode[T], classOfT: Class[T]): T = {
    //Enhancer.create(classOfT, new PosoPropertyAccessInterceptor(vxn)).asInstanceOf[T]
  //}

  def createSample(viewExpressionNode: ViewExpressionNode[_]): T =
    FieldReferenceLinker.executeAndRestoreLastAccessedFieldReference(_builder(viewExpressionNode))

  private val _builder: ViewExpressionNode[_] => T = {
    val klass = new ByteBuddy()
      .subclass(clasz)
      .method(ElementMatchers.not(ElementMatchers.isStatic().or(ElementMatchers.named("finalize").or(ElementMatchers.nameStartsWith("$")))))
      .intercept(
        MethodDelegation.to(classOf[PosoPropertyAccessInterceptorShim])
      )
      .defineField(PosoMetaData.ViewExpressionNodeFieldName, classOf[ViewExpressionNode[_]], Visibility.PUBLIC)
      .make()
      .load(clasz.getClassLoader)
      .getLoaded()

    val myConstructor = klass.getConstructor(constructor._1.getParameterTypes: _*)

    val viewExpressionNodeField = klass.getDeclaredField(PosoMetaData.ViewExpressionNodeFieldName)

    (viewExpressionNode: ViewExpressionNode[_]) => {
      val res = myConstructor.newInstance(constructor._2: _*).asInstanceOf[T]
      viewExpressionNodeField.set(res, viewExpressionNode)
      res
    }
  }

  private def _isImplicitMode = {
    
    val rowAnnotation = clasz.getAnnotation(classOf[Row])

    rowAnnotation == null ||
     rowAnnotation.fieldToColumnCorrespondanceMode == FieldToColumnCorrespondanceMode.IMPLICIT
  }

  private def _groupOfMembersIsProperty(property: (Option[Field], Option[Method], Option[Method], Set[Annotation])): Boolean  = {
    
    if(property._4.find(an => an.isInstanceOf[Transient]) != None)
      return false    

    val hasAField = property._1 != None
	
	val isAStaticField = property._1.map(f => Modifier.isStatic(f.getModifiers)).getOrElse(false)

    val hasGetter = property._2 != None &&
      ! classOf[java.lang.Void].isAssignableFrom(property._2.get.getReturnType) &&
      property._2.get.getParameterTypes.length == 0

    val hasSetter = property._3 != None &&
      property._3.get.getParameterTypes.length == 1
    
    val memberTypes = new ArrayBuffer[Class[_]]

    if(hasAField)
      memberTypes.append(property._1.get.getType)
    if(hasGetter)
      memberTypes.append(property._2.get.getReturnType)
    if(hasSetter)
      memberTypes.append(property._3.get.getParameterTypes.apply(0))    

    //not a property if it has no getter, setter or field
    if(memberTypes.size == 0)
      return false

    //verify that all types are compatible :
    val c = memberTypes.remove(0)
    for(c0 <- memberTypes) {
      if((!c0.isAssignableFrom(c)) && (!c.isAssignableFrom(c0)))
        return false
    }

    (hasAField, hasGetter, hasSetter) match {
      case (true,  false, false) => !isAStaticField
      case (false, true,  true)  => true
      case (true,  true,  true)  => true
      case (true,  true, false)  => true
      case a:Any => false
    }
  }

  private def _includeAnnotation(a: Annotation) =
   a.isInstanceOf[ColumnBase] || a.isInstanceOf[Transient] || a.isInstanceOf[OptionType]
  
  private def _addAnnotations(m: Field, s: HashSet[Annotation]) =
    for(a <- m.getAnnotations if _includeAnnotation(a))
      s.add(a)

  private def _addAnnotations(m: Method, s: HashSet[Annotation]) =
    for(a <- m.getAnnotations if _includeAnnotation(a))
      s.add(a)

  private def _includeFieldOrMethodType(c: Class[_]) =
    schema.fieldMapper.isSupported(c)
      //! classOf[Query[_]].isAssignableFrom(c)

  /**
    * Finds the Scala type of a field, getter, or setter (as used by Squeryl).
    */
  private def effectiveType(name: String, tpe: universe.Type): Option[universe.Type] = {
    val decoded = universe.TermName(name).decodedName
    val symbol = tpe.member(decoded)
    if (symbol.isTerm) {
      val term = symbol.asTerm
      if (term.isMethod) {
        val method = term.asMethod
        if (method.paramLists.isEmpty)
          Some(method.returnType)
        else if (method.returnType =:= universe.typeOf[Unit] &&
          method.paramLists.lengthCompare(1) == 0 &&
          method.paramLists(0).lengthCompare(1) == 0)
          Some(method.paramLists(0)(0).typeSignature)
        else None
      } else None
    } else None
  }

  /**
    * Finds the underlying type for a tagged type or an Option containing
    * a tagged type (e.g. Int in Int @@ MyInt or Option[Int @@ MyInt]).
    * The @@ type alias cannot be relied upon as it may have been re-aliased.
    * The de-aliased AnyRef refinement with its two type declarations is treated
    * as a sufficient clue that it's a tagged type. For non-tagged types, the
    * type inside the Option is returned, otherwise None. (The reason for this
    * quirk is that some generated case class methods return types that don't
    * have a corresponding Java class.)
    */
  private def findTaggedType(tpe: universe.Type): Option[universe.Type] =
    if (tpe.typeSymbol == universe.typeOf[Option[_]].typeSymbol)
      unpackTypeFromTag(tpe.typeArgs.head).orElse(Some(tpe.typeArgs.head))
    else
      unpackTypeFromTag(tpe)

  private def unpackTypeFromTag(tpe: universe.Type): Option[universe.Type] = {
    if (tpe.typeSymbol.fullName == "gov.wicourts.common.TagModule.$at$at")
      tpe.dealias.typeArgs.headOption
    else if (tpe.erasure =:= universe.typeOf[Object]) {
      val dealiased = tpe.dealias
      if (dealiased.decls.toList.length == 2 &&
        dealiased.decl(universe.TypeName("Tag")) != universe.NoSymbol)
        Some(dealiased.decl(universe.TypeName("Self")))
          .filterNot(_ == universe.NoSymbol)
          .map(_.asType.typeSignature)
      else
        None
    } else
      None
  }

  private def runtimeClass(tpe: universe.Type): Option[Class[_]] = {
    val mirror = universe.runtimeMirror(getClass.getClassLoader)
    try Some(mirror.runtimeClass(tpe))
    catch {
      // Thrown if the Scala type does not have a corresponding Java class
      case _: ClassNotFoundException => None
    }
  }

  private def taggedClass(name: String, tpe: universe.Type): Option[Class[_]] =
    effectiveType(name, scalaTpe).flatMap(findTaggedType).flatMap(runtimeClass)

  private def effectiveMethodType(method: Method): Class[_] =
    taggedClass(method.getName, scalaTpe).getOrElse(method.getReturnType)

  private def effectiveFieldType(field: Field): Class[_] =
    taggedClass(field.getName, scalaTpe).getOrElse(field.getType)

  private def _fillWithMembers(clasz: Class[_], members: ArrayBuffer[(Member,HashSet[Annotation])]) {
    def includeMethod(m: Method): Boolean =
      if (m.getDeclaringClass == classOf[Object])
        false
      else
        _includeFieldOrMethodType(effectiveMethodType(m))

    def includeField(f: Field): Boolean =
      if (f.getName.indexOf("$") != -1)
        false
      else
        _includeFieldOrMethodType(effectiveFieldType(f))

    for(m <-clasz.getMethods if includeMethod(m)) {
      m.setAccessible(true)
      val t = (m, new HashSet[Annotation])
      _addAnnotations(m, t._2)
      members.append(t)
    }

    for(m <- clasz.getDeclaredFields if includeField(m)) {
      m.setAccessible(true)
      val t = (m, new HashSet[Annotation])
      _addAnnotations(m, t._2)
      members.append(t)
    }

    val c = clasz.getSuperclass

    if(c != null)
      _fillWithMembers(c, members)
  }

  def refreshableFields: Iterable[FieldMetaData] =
    for {
      m <- fieldsMetaData if m.isDbManaged || m.isTriggerUpdated
    } yield m

  def dbManagedFields: Iterable[FieldMetaData] =
    for {
      m <- fieldsMetaData if m.isDbManaged
    } yield m

  def hasRefreshableFields = ! refreshableFields.isEmpty
}

object PosoMetaData {
  val InternalFieldPrefix: String = "$Squeryl$"
  val ViewExpressionNodeFieldName = s"${InternalFieldPrefix}viewExpressionNode"
}
