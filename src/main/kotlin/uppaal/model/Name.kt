package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root
import org.simpleframework.xml.Text

@Root(name = "name")
open class Name() : TextUppaalPojo {
    @field:Attribute(name = "x", required = false)
    var x: Int = 0

    @field:Attribute(name = "y", required = false)
    var y: Int = 0

    @field:Text(required = false)
    final override var content: String = ""


    override var parent: UppaalPojo? = null


    constructor(x: Int, y: Int, content: String) : this() {
        this.x = x
        this.y = y
        this.content = content
    }


    override fun toString() = content

    open fun clone(): Name = Name(x, y, content)
}

class SubTemplateName : Name {
    constructor() : super()
    constructor(x: Int, y: Int, content: String) : super(x, y, content)


    override fun clone(): SubTemplateName =
        SubTemplateName(x, y, content)
}