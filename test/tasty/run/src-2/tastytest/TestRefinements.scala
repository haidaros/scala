package tastytest

object TestRefinements extends Suite("TestRefinements") {
  class FooString extends Refinements.Foo {
    type T = String
    def foo = "I am foo"
  }

  val b = new Refinements.Bar[String, FooString]
  test(assert(b.bar(new FooString) == "I am foo"))

}
