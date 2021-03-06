package com.eclipsesource.schema

import com.eclipsesource.schema.test.JsonSpec
import org.specs2.mutable.Specification

class TypeSpec extends Specification with JsonSpec {

  "type draft4" in {
    import Version4._
    implicit val validator: SchemaValidator = SchemaValidator(Version4)
    validate("type")
  }
  "type draft7" in {
    import Version7._
    implicit val validator: SchemaValidator = SchemaValidator(Version7)
    validate("type", "draft7")
  }
}
