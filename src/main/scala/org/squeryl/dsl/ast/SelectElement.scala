package org.squeryl.dsl.ast

import collection.mutable.ArrayBuffer
import org.squeryl.internals._

/**
 * SelectElement are elements of a select list, they are either
 *  ValueSelectElement for composite expressions, i.e. select (x / 2) * y as Z from ....
 *  TupleSelectElement for group by or compute elements (TODO: document group by/compute)
 *  FieldSelectElement for table columns (that map to fields)
 *
 *  ExportSelectElement is a select element that refers to a SelectElement of an inner query.
 *
 * SelectElementReference are nodes in any clause other than select (where, having, composite expression, etc)
 *  that refer to a SelectElement  
 */
trait SelectElement extends ExpressionNode {

  def origin: QueryableExpressionNode

  def resultSetMapper: ResultSetMapper

  def alias: String

  def aliasSuffix: String

  def prepareColumnMapper(index: Int): Unit

  def prepareMapper: Unit

  //def isNull(rs: ResultSet): Boolean

  def isActive = _isActive

  var _isActive = false
  
  def expression: ExpressionNode

  /**
   * strictly for logging purposes, i.e. to display a more explicit AST
   */
  def typeOfExpressionToString: String

  override def children = List(expression)

  def write(sw: StatementWriter) = {
    expression.write(sw)
    sw.write(" as ")
    sw.write(alias)
  }
}

class TupleSelectElement
 (val origin: QueryableExpressionNode, val expression: ExpressionNode, indexInTuple: Int, isGroupTuple: Boolean)
    extends SelectElement {

  def resultSetMapper: ResultSetMapper = error("refactor me")
  
  def alias =
    if(isGroupTuple)
      "g" + indexInTuple
    else
      "c" + indexInTuple

  def aliasSuffix = alias
  
  var columnToTupleMapper: Option[ColumnToTupleMapper] = None

  def prepareColumnMapper(index: Int) = {}

  def typeOfExpressionToString: String =
    if(columnToTupleMapper == None)
      "unknown"
    else
      columnToTupleMapper.get.typeOfExpressionToString(indexInTuple)

  override def prepareMapper =
    if(columnToTupleMapper != None)
      columnToTupleMapper.get.activate(indexInTuple)

  //def isNull(rs: ResultSet): Boolean = columnToTupleMapper.get.isNull(indexInTuple, rs)


  override def toString =
    'TupleSelectElement + ":" + indexInTuple + ":" + writeToString
}

class FieldSelectElement
(val origin: QueryableExpressionNode, val fieldMataData: FieldMetaData, val resultSetMapper: ResultSetMapper)
  extends SelectElement {

  def alias = origin.alias + "_" + fieldMataData.name

  def aliasSuffix = fieldMataData.name
  
  val expression = new ExpressionNode {
    
    def write(sw: StatementWriter) =
      sw.write(origin.alias + "." + fieldMataData.name)
  }

  //def isNull(rs: ResultSet) = rs.getObject(columnMapper.get.index) == null

  def prepareColumnMapper(index: Int) =
    columnMapper = Some(new ColumnToFieldMapper(index, fieldMataData, this))

  private var columnMapper: Option[ColumnToFieldMapper] = None

  def prepareMapper =
    if(columnMapper != None) {
      resultSetMapper.addColumnMapper(columnMapper.get)
      resultSetMapper.isActive = true
      _isActive = true
    }
  
  def typeOfExpressionToString =
    fieldMataData.displayType
  
  override def toString =
    'FieldSelectElement + ":" + alias  
}

class ValueSelectElement
  (val expression: ExpressionNode, val resultSetMapper: ResultSetMapper, expressionType: Class[_])
     extends SelectElement with UniqueIdInAliaseRequired {

  def alias = "v" + uniqueId.get

  def aliasSuffix = alias

  var yieldPusher: Option[YieldValuePusher] = None

  def prepareColumnMapper(index: Int) =
    yieldPusher = Some(new YieldValuePusher(index, this, expressionType))
  
  def origin: QueryableExpressionNode = error("refactor me")

  //def isNull(rs: ResultSet) = error("implement me")

  def typeOfExpressionToString =
    if(yieldPusher == None)
      "unknown"
    else
      yieldPusher.get.selectElement.typeOfExpressionToString
  
  override def prepareMapper =
    if(yieldPusher != None) {
      resultSetMapper.addYieldValuePusher(yieldPusher.get)
      resultSetMapper.isActive = true
      _isActive = true
    }

  override def toString =
    'ValueSelectElement + ":" + expression.writeToString  
}


trait PathReferenceToSelectElement {
  self: ExpressionNode =>

  def selectElement: SelectElement

  def write(sw: StatementWriter) = {

    if(_useSite == selectElement.origin.parent.get)
      selectElement.expression.write(sw)
    else
      sw.write(path)
  }
  
  private def _useSite: QueryExpressionNode[_] = {

    var e: ExpressionNode = this

    do {
      e = e.parent.get
      if(e.isInstanceOf[QueryExpressionNode[_]])
        return e.asInstanceOf[QueryExpressionNode[_]]
    } while (e != None)

    error("could not determin use site of "+ this)
  }

  protected def path: String = {

    val origin = selectElement.origin

    if(origin.parent == None)
      return selectElement.alias

    if(origin.parent.get.isInstanceOf[UpdateStatement] ||
       origin.parent.get.asInstanceOf[QueryExpressionElements].inhibitAliasOnSelectElementReference)
      return selectElement.asInstanceOf[FieldSelectElement].fieldMataData.name

    val us = _useSite

    val ab = new ArrayBuffer[QueryableExpressionNode]

    var o:ExpressionNode = origin

    do {
      if(o.isInstanceOf[QueryableExpressionNode])
        ab.prepend(o.asInstanceOf[QueryableExpressionNode])
      o = o.parent.get
    } while(o != us && o.parent != None)

    if(ab.size == 1)
      ab.remove(0).alias + "." + selectElement.aliasSuffix
    else
      ab.remove(0).alias + "." + ab.map(n=>n.alias).mkString("_") + "_" + selectElement.aliasSuffix
  }
}


/**
 * All nodes that refer to a SelectElement are SelectElementReference,
 * with the exception of SelectElement that refer to an inner query's SelectElement,
 * these are ExportedSelectElement
 */
class SelectElementReference
  (val selectElement: SelectElement)
    extends ExpressionNode with PathReferenceToSelectElement {

  override def toString =
    'SelectElementReference + ":" + Utils.failSafeString(path) + ":" + selectElement.typeOfExpressionToString

  override def write(sw: StatementWriter) =
    sw.write(path)
}

/**
 * SelectElement that refer to a SelectElement of an inner query 
 */
class ExportedSelectElement
  (val selectElement: SelectElement)
    extends SelectElement
    with PathReferenceToSelectElement {

  def resultSetMapper = selectElement.resultSetMapper

  override def prepareMapper =
    selectElement.prepareMapper

  def prepareColumnMapper(index: Int) =
    selectElement.prepareColumnMapper(index)

  def typeOfExpressionToString =
    selectElement.typeOfExpressionToString
  
  def origin = selectElement.origin

  def aliasSuffix = selectElement.aliasSuffix

  //def isNull(rs: ResultSet) = selectElement.isNull(rs)

  val expression = new ExpressionNode {

    def write(sw: StatementWriter) = error("refactor me") //sw.write(path)
  }

  def alias = error("refactor me")

  override def toString =
    'ExportedSelectElement + ":" + path

  override def write(sw: StatementWriter) = {
    val p = path
    sw.write(p)
    sw.write(" as ")
    sw.write(p.replace('.','_'))
  }
}