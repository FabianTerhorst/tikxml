/*
 * Copyright (C) 2015 Hannes Dorfmann
 * Copyright (C) 2015 Tickaroo, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.tickaroo.tikxml.processor.generator

import com.squareup.javapoet.*
import com.tickaroo.tikxml.TikXmlConfig
import com.tickaroo.tikxml.XmlReader
import com.tickaroo.tikxml.XmlWriter
import com.tickaroo.tikxml.processor.field.AnnotatedClass
import com.tickaroo.tikxml.typeadapter.AttributeBinder
import com.tickaroo.tikxml.typeadapter.ChildElementBinder
import com.tickaroo.tikxml.typeadapter.TypeAdapter
import java.io.IOException
import java.util.*
import java.util.Map
import javax.annotation.processing.Filer
import javax.lang.model.element.Modifier
import javax.lang.model.util.Elements

/**
 * This class takes an [com.tickaroo.tikxml.processor.field.AnnotatedClass] as input
 * and generates a [com.tickaroo.tikxml.TypeAdapter] (java code) for it
 * @author Hannes Dorfmann
 * @since 1.0
 */
class TypeAdapterCodeGenerator2(private val filer: Filer, private val elementUtils: Elements, private val typeConvertersForPrimitives: Set<String>) {


    /**
     * Generates an [com.tickaroo.tikxml.TypeAdapter] for the given class
     */
    fun generateCode(annotatedClass: AnnotatedClass) {

        val annotatedClassType = getClassToParseInto(annotatedClass)
        val genericParamTypeAdapter = ParameterizedTypeName.get(ClassName.get(TypeAdapter::class.java), annotatedClassType)

        val customTypeConverterManager = CustomTypeConverterManager()
        val codeGenUtils = CodeGenUtils(customTypeConverterManager, typeConvertersForPrimitives, annotatedClassType)

        val constructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addCode(codeGenUtils.generateAttributeBinders(annotatedClass))

        for ((xmlName, xmlElement) in annotatedClass.childElements) {
            constructorBuilder.addStatement("${CodeGenUtils.childElementBindersParam}.put(\$S, \$L)", xmlName, xmlElement.generateReadXmlCode(codeGenUtils))
        }


        //
        // Generate code
        //
        val adapterClassBuilder = TypeSpec.classBuilder(annotatedClass.simpleClassName + TypeAdapter.GENERATED_CLASS_SUFFIX)
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(genericParamTypeAdapter)


        generateFields(annotatedClass, adapterClassBuilder, customTypeConverterManager)

        adapterClassBuilder.addMethod(constructorBuilder.build())
                .addMethod(generateFromXmlMethod(annotatedClass).build())
                .addMethod(generateToXmlMethod(annotatedClass).build())


        val packageElement = elementUtils.getPackageOf(annotatedClass.element)
        val packageName = if (packageElement.isUnnamed) "" else packageElement.toString()

        val javaFile = JavaFile.builder(packageName, adapterClassBuilder.build()).build()
        javaFile.writeTo(filer)
    }

    /**
     * Generates the fields
     */
    private inline fun generateFields(annotatedClass: AnnotatedClass, adapterClassBuilder: TypeSpec.Builder, customTypeConverterManager: CustomTypeConverterManager) {

        val targetClassToParseInto = getClassToParseInto(annotatedClass)

        if (annotatedClass.hasAttributes()) {
            val attributeBinderMapField = ParameterizedTypeName.get(ClassName.get(Map::class.java),
                    ClassName.get(String::class.java), ParameterizedTypeName.get(ClassName.get(AttributeBinder::class.java), targetClassToParseInto))
            val attributeBinderHashMapField = ParameterizedTypeName.get(ClassName.get(HashMap::class.java),
                    ClassName.get(String::class.java), ParameterizedTypeName.get(ClassName.get(AttributeBinder::class.java), targetClassToParseInto))

            // TODO use ArrayMap
            adapterClassBuilder.addField(
                    FieldSpec.builder(attributeBinderMapField, CodeGenUtils.attributeBindersParam, Modifier.PRIVATE)
                            .initializer("new  \$T()", attributeBinderHashMapField)
                            .build())
        }

        if (annotatedClass.hasChildElements()) {
            val childElementBinderMapField = ParameterizedTypeName.get(ClassName.get(Map::class.java),
                    ClassName.get(String::class.java), ParameterizedTypeName.get(ClassName.get(ChildElementBinder::class.java), targetClassToParseInto))
            val childElementBinderHashMapField = ParameterizedTypeName.get(ClassName.get(HashMap::class.java),
                    ClassName.get(String::class.java), ParameterizedTypeName.get(ClassName.get(ChildElementBinder::class.java), targetClassToParseInto))

            // TODO use ArrayMap
            adapterClassBuilder.addField(
                    FieldSpec.builder(childElementBinderMapField, CodeGenUtils.childElementBindersParam, Modifier.PRIVATE)
                            .initializer("new  \$T()", childElementBinderHashMapField)
                            .build())
        }


        // Add fields from TypeConverter
        for ((qualifiedConverterClass, fieldName) in customTypeConverterManager.converterMap) {
            val converterClassName = ClassName.get(elementUtils.getTypeElement(qualifiedConverterClass))
            adapterClassBuilder.addField(FieldSpec.builder(converterClassName, fieldName, Modifier.PRIVATE).initializer("new \$T()", converterClassName).build())
        }
    }

    /**
     * Generates the method to parse xml.
     */
    private inline fun generateFromXmlMethod(annotatedClass: AnnotatedClass): MethodSpec.Builder {

        val reader = CodeGenUtils.readerParam
        val config = CodeGenUtils.tikConfigParam
        val value = CodeGenUtils.valueParam
        val targetClassToParseInto = getClassToParseInto(annotatedClass)
        val textContentStringBuilder = "textContentBuilder"

        val builder = MethodSpec.methodBuilder("fromXml")
                .returns(ClassName.get(annotatedClass.element))
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override::class.java)
                .addParameter(XmlReader::class.java, reader)
                .addParameter(TikXmlConfig::class.java, config)
                .addException(IOException::class.java)
                .addStatement("\$T \$L = new \$T()", targetClassToParseInto, value, targetClassToParseInto)


        if (annotatedClass.hasTextContent()) {
            builder.addStatement("\$T \$L = new \$T()", StringBuilder::class.java, textContentStringBuilder, StringBuilder::class.java)
        }

        //
        // Read attributes
        //
        if (annotatedClass.hasAttributes()) {
            // consume attributes
            builder.beginControlFlow("while(\$L.hasAttribute())", reader)
                    .addStatement("String attributeName = \$L.nextAttributeName()", reader)
                    .addStatement("\$T attributeBinder = ${CodeGenUtils.attributeBindersParam}.get(attributeName)", ParameterizedTypeName.get(ClassName.get(AttributeBinder::class.java), targetClassToParseInto))
                    .beginControlFlow("if (attributeBinder != null)")
                    .addStatement("attributeBinder.fromXml(\$L, \$L, \$L)", reader, config, value)
                    .nextControlFlow("else")
                    .beginControlFlow("if (\$L.exceptionOnUnreadXml())", config)
                    .addStatement("throw new \$T(\$S+\$L.nextAttributeName()+\$S+\$L.getPath()+\$S)", IOException::class.java,
                            "Could not map the xml attribute with the name '",
                            reader,
                            "' at path ",
                            reader,
                            " to java class. Have you annotated such a field in your java class to map this xml attribute? Otherwise you can turn this error message off with TikXml.Builder().exceptionOnUnreadXml(false).build().")
                    .endControlFlow() // End if
                    .addStatement("\$L.skipAttributeValue()", reader)
                    .endControlFlow() // end if attributeBinder != null
                    .endControlFlow() // end while hasAttribue()

        } else {
            // Skip attributes if there are any
            builder.beginControlFlow("if (\$L.hasAttribute())", reader)
                    .beginControlFlow("if (\$L.exceptionOnUnreadXml())", config)
                    .addStatement("throw new \$T(\$S+\$L.nextAttributeName()+\$S+\$L.getPath()+\$S)", IOException::class.java,
                            "Could not map the xml attribute with the name '",
                            reader,
                            "' at path ",
                            reader,
                            " to java class. Have you annotated such a field in your java class to map this xml attribute? Otherwise you can turn this error message off with TikXml.Builder().exceptionOnUnreadXml(false).build().")
                    .endControlFlow()
                    .beginControlFlow("while(\$L.hasAttribute())", reader)
                    .addStatement("\$L.skipAttribute()", reader)
                    .endControlFlow()
                    .endControlFlow()
        }

        //
        // Read child elements and text content
        //
        if (annotatedClass.hasChildElements() || annotatedClass.hasChildElements()) {

            builder.beginControlFlow("while(true)")
                    .beginControlFlow("if (\$L.hasElement())", reader)
                    
                    .addStatement("\$L.beginElement()", reader)
                    .addStatement("String elementName = \$L.nextElementName()", reader)
                    .addStatement("\$T childElementBinder = \$L.get(elementName)", ParameterizedTypeName.get(ClassName.get(ChildElementBinder::class.java), targetClassToParseInto), CodeGenUtils.childElementBindersParam)
                    .beginControlFlow("if (childElementBinder != null)")
                    .addStatement("childElementBinder.fromXml(\$L, \$L, \$L)", reader, config, value)
                    .addStatement("\$L.endElement()", reader)
                    .nextControlFlow("else if (\$L.exceptionOnUnreadXml())", config)
                    .addStatement("throw new \$T(\$S+elementName+\$L.getPath()+\$S)", IOException::class.java,
                            "Could not map the xml element with the tag name at path '",
                            reader,
                            "' to java class. Have you annotated such a field in your java class to map this xml attribute? Otherwise you can turn this error message off with TikXml.Builder().exceptionOnUnreadXml(false).build().")
                    .nextControlFlow("else")
                    .addStatement("\$L.skipRemainingElement()", reader)
                    .endControlFlow() // end else skip remaining element

                    .nextControlFlow("else if (\$L.hasTextContent())", reader)

            if (annotatedClass.hasTextContent()) {
                builder.addStatement("\$L.append(\$L.nextTextContent())", textContentStringBuilder, reader)
            } else {
                builder.beginControlFlow("if (\$L.exceptionOnUnreadXml())", config)
                        .addStatement("throw new \$T(\$S+\$L.getPath()+\$S)", IOException::class.java,
                                "Could not map the xml element's text content at path '",
                                reader,
                                " to java class. Have you annotated such a field in your java class to map the xml element's text content? Otherwise you can turn this error message off with TikXml.Builder().exceptionOnUnreadXml(false).build().")
                        .endControlFlow()
                        .addStatement("\$L.skipTextContent()", reader)
            }

            builder.nextControlFlow("else")
                    .addStatement("break") // quite while loop
                    .endControlFlow() // End else
                    .endControlFlow() // End while


        } else {
            // Skip child elements and text content
            builder.beginControlFlow("while (\$L.hasElement() || \$L.hasTextContent())", reader, reader)
                    .beginControlFlow("if (\$L.hasElement())", reader)
                    .beginControlFlow("if (\$L.exceptionOnUnreadXml())", config)
                    .addStatement("throw new \$T(\$S+\$L.nextElementName()+\$S+\$L.getPath()+\$S)", IOException::class.java,
                            "Could not map the xml element with the tag name '",
                            reader,
                            "' at path ",
                            reader,
                            " to java class. Have you annotated such a field in your java class to map this xml attribute? Otherwise you can turn this error message off with TikXml.Builder().exceptionOnUnreadXml(false).build().")
                    .endControlFlow() // End if throw exception
                    .beginControlFlow("while(\$L.hasElement())", reader)
                    .addStatement("\$L.skipRemainingElement()", reader)
                    .endControlFlow() // End while skiping element
                    // Skip Text Content
                    .nextControlFlow("else if (\$L.hasTextContent())", reader)
                    .beginControlFlow("if (\$L.exceptionOnUnreadXml())", config)
                    .addStatement("throw new \$T(\$S+\$L.getPath()+\$S)", IOException::class.java,
                            "Could not map the xml element's text content at path '",
                            reader,
                            " to java class. Have you annotated such a field in your java class to map the xml element's text content? Otherwise you can turn this error message off with TikXml.Builder().exceptionOnUnreadXml(false).build().")
                    .endControlFlow() // End if throw exception
                    .addStatement("\$L.skipTextContent()", reader)
                    .endControlFlow() // End  hasTextContent()
                    .endControlFlow() // end while

        }

        // assign Text Content
        if (annotatedClass.hasTextContent()) {
            val field = annotatedClass.textContentField!!
            builder.addCode(field.accessPolicy.resolveAssignment("$textContentStringBuilder.toString()"))
            // TODO constructor support
        }

        // TODO Constructor support: copy value to constructor
        builder.addStatement("return \$L", value)

        return builder;
    }

    private inline fun generateToXmlMethod(annotatedClass: AnnotatedClass): MethodSpec.Builder {

        val writer = CodeGenUtils.writerParam
        val config = CodeGenUtils.tikConfigParam
        val value = CodeGenUtils.valueParam

        val builder = MethodSpec.methodBuilder("toXml")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override::class.java)
                .returns(Void.TYPE)
                .addParameter(XmlWriter::class.java, writer)
                .addParameter(TikXmlConfig::class.java, config)
                .addParameter(ClassName.get(annotatedClass.element), value)
                .addException(IOException::class.java)

        return builder
    }

    /**
     * Get the Type that is used for generic types like [AttributeBinder] and [ChildElementBinder] to parse into that.
     */
    private inline fun getClassToParseInto(annotatedClass: AnnotatedClass) =
            if (annotatedClass.annotatedConstructor) {
                throw UnsupportedOperationException("Not implemented yet")
                // TODO return type of not generated Helper
            } else {
                ClassName.get(annotatedClass.element)
            }
}