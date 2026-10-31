package dev.teslanavi.navigation

import dev.teslanavi.protocol.NavigationDestination

/** Cosmetic names and tiny coordinate changes must not restart an unchanged destination route. */
internal fun sameGuidanceDestination(a: NavigationDestination?, b: NavigationDestination?): Boolean {
    if (a == b) return true
    if (a !is NavigationDestination.Active || b !is NavigationDestination.Active) return false
    val first = a.point; val second = b.point
    return if (first != null && second != null) RouteGeometry.distance(first, second) <= 20.0
    else first == null && second == null && a.name?.trim() == b.name?.trim()
}
