package com.elevman.ui

/** The four top-level screens, in the order they sit in the bottom bar. */
enum class Tab(val route: String, val label: String) {
    SEARCH("search", "Search"),
    LIBRARY("library", "Library"),
    ELEVATORS("elevators", "Elevators"),
    BACKUP("backup", "Backup"),
}
