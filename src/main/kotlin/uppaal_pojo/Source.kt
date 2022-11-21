package uppaal_pojo

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root

@Root(name = "source")
class Source() : UppaalPojo {
    @field:Attribute(name = "ref")
    lateinit var ref: String

    constructor(ref: String) : this() {
        this.ref = ref
    }
}