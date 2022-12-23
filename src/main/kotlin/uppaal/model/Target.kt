package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root

@Root(name = "target")
class Target() : UppaalPojo {
    @field:Attribute(name = "ref")
    lateinit var ref: String

    constructor(ref: String) : this() {
        this.ref = ref
    }
}