package simple_trees

class GenericMethod {
  def usesTypeParam[T](): Option[T] = None
  def usesTermParam(i: Int): Option[Int] = None

  def identity[T](x: T): T = x
}
