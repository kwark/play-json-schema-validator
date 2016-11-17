package com.eclipsesource.schema.internal.validators

import java.util.regex.Pattern

import com.eclipsesource.schema._
import com.eclipsesource.schema.internal.SchemaRefResolver._
import com.eclipsesource.schema.internal._
import com.eclipsesource.schema.internal.validation.VA
import play.api.libs.json._

import scalaz.{ReaderWriterState, Success}

object ObjectValidator extends SchemaTypeValidator[SchemaObject] {

  private def resultOnly(va: VA[JsValue]) = ((), (), va)

  override def validate(schema: SchemaObject, json: => JsValue, context: SchemaResolutionContext): VA[JsValue] = {
    json match {
      case jsObject@JsObject(props) =>
        val validation = for {
        // TODO: updatedSchema is schema
          updatedSchema <- validateDependencies(schema, jsObject)
          remaining <- validateProps(updatedSchema, jsObject)
          unmatched <- validatePatternProps(updatedSchema, jsObject.fields)
          _ <- validateAdditionalProps(updatedSchema, unmatched.intersect(remaining), json)
          _ <- validateMinProperties(updatedSchema, jsObject)
          _ <- validateMaxProperties(updatedSchema, jsObject)
        } yield updatedSchema

        val (_, _, result) = validation.run(context, Success(json))
        result
      case _ =>
        Success(json)
    }
  }

  private def validateProps(schema: SchemaObject, json: => JsObject): ValidationStep[Props] =
    ReaderWriterState { (context, status) =>

      val required = schema.constraints.required.getOrElse(List.empty[String])

      val validated = schema.properties.foldLeft(List.empty[(String, VA[JsValue])])((props, attr) =>
        json \ attr.name match {
          case _: JsUndefined => if (required.contains(attr.name)) {
            attr.name ->
              Results.failureWithPath(
                Keywords.Object.Required,
                s"Property ${attr.name} missing",
                context,
                json
              ) :: props
          } else {
            props
          }
          case JsDefined(value) => (attr.name ->
            attr.schemaType.validate(
              value,
              context.updateScope(
                _.copy(
                  schemaPath = context.schemaPath \ "properties" \ attr.name,
                  instancePath = context.instancePath \ attr.name
                )
              )
            )) :: props
        }
      )

      val missing = required.filterNot(req => validated.exists(_._1 == req))
        .foldLeft(List.empty[(String, VA[JsValue])]) { (acc, req) =>
          json \ req match {
            case _: JsUndefined =>
              val result = req ->
                Results.failureWithPath(
                  Keywords.Object.Required,
                  s"Property $req missing",
                  context,
                  json
                )
              result :: acc
            case _ => acc
          }
        }

      val result = validated ++ missing

      val validatedProperties = result.map(_._1)
      val unvalidatedProps: Props = json.fields.filterNot(field =>
        validatedProperties.contains(field._1)
      )

      ((), unvalidatedProps, Results.merge(status, Results.aggregateAsObject(result, context)))
    }

  private def validatePatternProps(schema: SchemaObject, props: Props): ValidationStep[Props] =
    ReaderWriterState { (context, status) =>

      // find all matching properties and validate them
      val validated: Seq[(String, VA[JsValue])] = props.flatMap {
        prop => {
          val matchedPatternProperties: Iterable[(String, SchemaType)] = schema.constraints.patternProps.getOrElse(Seq.empty).filter(pp => {
            val pattern = Pattern.compile(pp._1)
            val matcher = pattern.matcher(prop._1)
            matcher.find()
          })
          matchedPatternProperties.map(pp => {
            prop._1 -> pp._2.validate(prop._2, context.updateScope(
              _.copy(
                schemaPath = context.schemaPath \ "properties" \ prop._1,
                instancePath = context.instancePath \ prop._1
              )
            ))
          }
          )
        }
      }

      val validatedProperties = validated.map(_._1)
      val unmatchedProps = props.filterNot(prop =>
        validatedProperties.contains(prop._1)
      )

      ((), unmatchedProps, Results.merge(status, Results.aggregateAsObject(validated, context)))
    }

  private def validateAdditionalProps(schema: SchemaObject, unmatchedFields: Props, json: JsValue): ValidationStep[Unit] = {

    def validateUnmatched(schemaType: SchemaType, context: SchemaResolutionContext): VA[JsValue] = {
      val validated = unmatchedFields.map { attr =>
        attr._1 -> schemaType.validate(
          attr._2,
          context.updateScope(
            _.copy(
              schemaPath = context.schemaPath \ Keywords.Object.AdditionalProperties,
              instancePath = context.instancePath \ attr._1
            )
          )
        )
      }
      Results.aggregateAsObject(validated, context)
    }

    ReaderWriterState { (context, status) =>

      if (unmatchedFields.isEmpty) {
        resultOnly(status)
      } else {
        schema.constraints.additionalPropertiesOrDefault match {
          case SchemaValue(JsBoolean(enabled)) =>
            if (enabled) resultOnly(Results.merge(status, Success(JsObject(unmatchedFields))))
            else resultOnly(
              Results.merge(status,
                Results.failureWithPath(
                  Keywords.Object.AdditionalProperties,
                  s"Additional properties are not allowed but found ${unmatchedFields.map(f => s"'${f._1}'").mkString(" and ")}.",
                  context,
                  json
                )
              ))
          case additionalProp =>
            val validationStatus = validateUnmatched(additionalProp, context)
            resultOnly(Results.merge(status, validationStatus))
        }
      }
    }
  }

  private def validateDependencies(schema: SchemaObject, json: JsObject): ValidationStep[SchemaObject] = {

    def validatePropertyDependency(propName: String, dependencies: Seq[String], context: SchemaResolutionContext): VA[JsValue] = {

      // check if property is present at all
      val mandatoryProps = json.fields.find(_._1 == propName)
        .map(_ => dependencies)
        .getOrElse(Seq.empty[String])

      // if present, make sure all dependencies are fulfilled
      val result = mandatoryProps.map(prop => json.fields.find(_._1 == prop).fold(
        prop -> Results.failureWithPath(
          Keywords.Object.Dependencies,
          s"Missing property dependency $prop.",
          context.updateScope(_.copy(
            schemaPath = context.schemaPath \ prop,
            instancePath = context.instancePath \ prop
          )),
          json
        )
      )(field => Results.success(field)))

      Results.aggregateAsObject(result, context)
    }

    ReaderWriterState { (context, status) =>

      val dependencies = schema.constraints.dependencies.getOrElse(Seq.empty)
      val updatedStatus = dependencies.foldLeft(status) { case (currStatus, dep) =>
        dep match {
          case (name, SchemaValue(JsArray(values))) =>
            // collecting strings should not be necessary at this point
            val validated = validatePropertyDependency(name, values.collect { case JsString(str) => str }, context)
            Results.merge(currStatus, validated)
          case (name, dep: SchemaObject) if json.keys.contains(name) =>
            val validated = dep.validate(json, context)
            Results.merge(currStatus, validated)
          case _ => currStatus
        }
      }

      ((), schema, updatedStatus)
    }
  }

  def validateMaxProperties(schema: SchemaObject, json: JsObject): ReaderWriterState[SchemaResolutionContext, Unit, VA[JsValue], Unit] = {
    ReaderWriterState { (context, status) =>
      val size = json.fields.size
      val result: VA[JsValue] = schema.constraints.maxProperties match {
        case None => Success(json)
        case Some(max) =>
          if (size <= max)  Success(json)
          else  Results.failureWithPath(
            Keywords.Object.MaxProperties,
            s"Found $size properties, but only a maximum of $max properties is allowed",
            context,
            json
          )
      }
      ((), (), Results.merge(status, result))
    }
  }

  def validateMinProperties(schema: SchemaObject, json: JsObject): ReaderWriterState[SchemaResolutionContext, Unit, VA[JsValue], Unit] = {
    ReaderWriterState { (context, status) =>
      val size = json.fields.size
      val result: VA[JsValue] = schema.constraints.minProperties match {
        case None => Success(json)
        case Some(min) => if (size >= min) {
          Success(json)
        } else {
          Results.failureWithPath(
            Keywords.Object.MinProperties,
            s"Found $size properties, but at least $min ${if (min == 1) "property needs" else "properties need"} to be present.",
            context,
            json
          )
        }
      }
      ((), (), Results.merge(status, result))
    }
  }
}

