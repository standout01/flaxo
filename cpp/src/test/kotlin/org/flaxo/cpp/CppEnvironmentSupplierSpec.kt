package org.flaxo.cpp

import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldNotThrow
import org.amshove.kluent.shouldThrow
import org.flaxo.core.framework.BashInputOutputTestingFramework
import org.flaxo.core.framework.JUnitTestingFramework
import org.flaxo.core.lang.BashLang
import org.flaxo.core.lang.JavaLang
import org.flaxo.core.lang.`C++Lang`
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on

object CppEnvironmentSupplierSpec : Spek({

    describe("c++ environment supplier") {
        on("initialization with an unsupported language") {
            it("should throw an exception") {
                {
                    CppEnvironmentSupplier(JavaLang, BashLang, BashInputOutputTestingFramework)
                } shouldThrow CppEnvironmentException::class
            }
        }

        on("initialization with an unsupported testing language") {
            it("should throw an exception") {
                {
                    CppEnvironmentSupplier(`C++Lang`, JavaLang, BashInputOutputTestingFramework)
                } shouldThrow CppEnvironmentException::class
            }
        }

        on("initialization with an unsupported testing framework") {
            it("should throw an exception") {
                {
                    CppEnvironmentSupplier(`C++Lang`, BashLang, JUnitTestingFramework)
                } shouldThrow CppEnvironmentException::class
            }
        }

        on("initialization with supported technologies") {
            it("should no throw exception") {
                {
                    CppEnvironmentSupplier(`C++Lang`, BashLang, BashInputOutputTestingFramework)
                } shouldNotThrow CppEnvironmentException::class
            }
        }

        on("getting environment for C++, Bash, IO tests") {
            val supplier = CppEnvironmentSupplier(`C++Lang`, BashLang, BashInputOutputTestingFramework)
            val environment = supplier.environment()

            it("should produce CppBashEnvironment") {
                environment shouldBeInstanceOf CppBashEnvironment::class
            }
        }
    }
})