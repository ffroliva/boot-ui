package io.github.jdubois.bootui.engine.architecture.generatedfixtures

import java.io.IOException
import java.io.Writer

object ApiUtil {
    fun setExampleResponse(writer: Writer, example: String) {
        try {
            writer.write(example)
        } catch (exception: IOException) {
            throw RuntimeException(exception)
        }
    }
}

class Container {
    companion object {
        fun run() {
            throw RuntimeException()
        }
    }
}
