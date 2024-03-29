package uppaal.model

import org.simpleframework.xml.Root
import org.simpleframework.xml.Text

@Root(name = "parameter")
class Parameter() : TextUppaalPojo {
    @field:Text
    override var content: String = ""

    override var parent: UppaalPojo? = null

    constructor(content: String) : this() {
        this.content = content
    }

    override fun toString() = content

    fun clone(): Parameter = Parameter(content)
}