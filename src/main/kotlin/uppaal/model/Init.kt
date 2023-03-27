package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root

@Root(name = "init")
class Init() : UppaalPojo {
    @field:Attribute(name = "ref")
    lateinit var ref: String

    override var parent: UppaalPojo? = null

    constructor(ref: String) : this() {
        this.ref = ref
    }

    fun clone(): Init = Init(ref)
}