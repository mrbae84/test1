package vn.trinhdoc.app

/**
 * Khung trang HTML chung. Giao diện và cách lật trang nằm trong assets/reader.css và
 * assets/reader.js (phục vụ qua https://reader.local/).
 */
object Html {

    fun page(c: ReaderContent, settingsJson: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=8,user-scalable=yes\">" +
            "<link rel=\"stylesheet\" href=\"/reader.css\"></head><body class=\"" + c.bodyClass + "\">" +
            c.body() +
            "<script>window.KIND='" + c.kind + "';window.S=" + settingsJson + ";</script>" +
            "<script src=\"/reader.js\"></script></body></html>"
}
