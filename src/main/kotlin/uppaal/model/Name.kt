package uppaal.model

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root
import org.simpleframework.xml.Text

@Root(name = "name")
open class Name : TextUppaalPojo {
    @field:Attribute(name = "x", required = false)
    var x: Int = 0

    @field:Attribute(name = "y", required = false)
    var y: Int = 0

    @field:Text(required = false)
    override var content: String = ""

    override fun toString() = content
}

class SubTemplateName : Name()