package org.flaxo.common

/**
 * Testing frameworks enum.
 */
enum class Framework(override val alias: String): Named, Aliased {
    JUnit("junit"),
    Spek("spek"),
    BashIO("bash")
    ;

    override fun toString() = alias

    companion object {

        /**
         * Returns an associated framework instance.
         */
        fun from(alias: String?): Framework? = if (alias == null) null else values().find { it.alias == alias }
    }
}
