package uppaal.model

import org.simpleframework.xml.Root
import org.simpleframework.xml.Text

@Root(name = "declaration")
class Declaration : UppaalPojo {
    @field:Text()
    lateinit var content: String
}