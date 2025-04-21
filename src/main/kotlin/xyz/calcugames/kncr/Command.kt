package xyz.calcugames.kncr

import kotlinx.serialization.Serializable

@Serializable
class Command(val cmd: String, val extra: String?)