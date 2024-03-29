package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root

@Root(name = "target")
class Target() : UppaalPojo {
    @field:Attribute(name = "ref")
    lateinit var ref: String

    override var parent: UppaalPojo? = null

    constructor(ref: String) : this() {
        this.ref = ref
    }

    fun clone(): Target = Target(ref)
}