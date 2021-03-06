package com.eclipsesource.schema

import com.eclipsesource.schema.test.JsonSpec
import org.specs2.mutable.Specification

class AdditionalPropertiesSpec extends Specification with JsonSpec {

  "additionalProperties draft4" in {
    import Version4._
    implicit val validator = SchemaValidator(Version4)
    validate("additionalProperties")
  }

  "additionalProperties draft7" in {
    import Version7._
    implicit val validator = SchemaValidator(Version7)
    validate("additionalProperties", "draft7")
  }
}
