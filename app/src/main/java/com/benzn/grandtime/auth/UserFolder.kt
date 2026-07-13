package com.benzn.grandtime.auth

/** 登录态媒体作用域:folder = 目录段;namePrefix = 文件名前缀(null → 用 kind 前缀 VID/AUD/IMG)。 */
data class MediaScope(val folder: String, val namePrefix: String?)

object UserFolder {
    /** 非字母数字折叠为单个下划线、小写、去首尾下划线;空 → "user"。 */
    fun sanitize(name: String): String {
        val s = name.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return s.ifBlank { "user" }
    }

    /** 用户名优先 name→email→"user";目录 = <username>_<sub 前 8 位字母数字>。 */
    fun derive(name: String?, email: String?, sub: String): MediaScope {
        val username = sanitize(name ?: email ?: "user")
        val subShort = sub.filter { it.isLetterOrDigit() }.take(8)
        return MediaScope("${username}_$subShort", username)
    }
}
