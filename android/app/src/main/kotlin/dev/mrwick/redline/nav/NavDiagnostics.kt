package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.NavFrame

internal object NavDiagnostics {
    fun quoted(value: CharSequence?): String = value?.toString()?.let {
        "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
            .replace("\u00a0", "\\u00a0").replace("\u202f", "\\u202f") + "\""
    } ?: "null"

    fun fields(frame: NavFrame): String =
        "trace=${frame.traceId ?: "none"} arrow=${frame.maneuverId} " +
            "next=${quoted(frame.distNext)} unit=${quoted(frame.distNextUnit)} " +
            "total=${quoted(frame.distTotal)} totalUnit=${quoted(frame.distTotalUnit)} " +
            "eta=${quoted(frame.eta)} status=${frame.status} continue=${frame.continueFlag}"
}
