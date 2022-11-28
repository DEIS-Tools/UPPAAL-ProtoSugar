package uppaal_pojo

import org.simpleframework.xml.Root
import org.simpleframework.xml.Text

@Root(name = "parameter")
class Parameter() : UppaalPojo {
    @field:Text
    lateinit var content: String

    constructor(content: String) : this() {
        this.content = content
    }
}