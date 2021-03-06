package com.eclipsesource.schema

import com.eclipsesource.schema.test.JsonSpec
import org.specs2.mutable.Specification

class DefinitionsSpec extends Specification with JsonSpec {

  "validate draft4" in {
    import Version4._
    implicit val validator = SchemaValidator(Version4)
    validate("definitions")
  }

  "validate draft7" in {
    import Version7._
    implicit val validator = SchemaValidator(Version7)
    validate("definitions", "draft7")
  }

}
