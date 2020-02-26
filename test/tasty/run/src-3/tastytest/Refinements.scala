package tastytest

object Refinements {

  trait Foo{
    type T <: AnyRef
    def foo: T
  }

  class Bar[Member, FooMember <: Foo {type T = Member}] {
    def bar(member: FooMember): Member = {
      member.foo
    }
  }

}
