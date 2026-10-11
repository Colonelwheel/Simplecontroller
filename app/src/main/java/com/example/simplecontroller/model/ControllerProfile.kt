package com.example.simplecontroller.model

import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.Locale

const val CONTROLLER_PROFILE_FORMAT_VERSION = 5
const val GAME_MOUSE_CONTROLLER_PROFILE_FORMAT_VERSION = 6
const val BUTTON_AIM_GAME_MOUSE_CONTROLLER_PROFILE_FORMAT_VERSION = 7
const val CAMERA_FOLLOW_CONTROLLER_PROFILE_FORMAT_VERSION = 8
const val BUTTON_AIM_BOUNDARY_CANCEL_CONTROLLER_PROFILE_FORMAT_VERSION = 9
const val MAX_SUPPORTED_CONTROLLER_PROFILE_FORMAT_VERSION = BUTTON_AIM_BOUNDARY_CANCEL_CONTROLLER_PROFILE_FORMAT_VERSION

@Serializable
data class ControllerPage(
    val id: String = "",
    val name: String = "",
    val controls: List<Control> = emptyList(),
    val portraitGeometry: PageOrientationGeometry? = null,
    val landscapeGeometry: PageOrientationGeometry? = null
)

@Serializable
data class ControllerProfile(
    val formatVersion: Int = CONTROLLER_PROFILE_FORMAT_VERSION,
    val homePageId: String = "",
    val pages: List<ControllerPage> = emptyList()
)

/** Keep legacy-only files readable by earlier apps; new mouse controls require an aware reader. */
fun ControllerProfile.requiredFormatVersion(): Int = when {
    pages.any { page -> page.controls.any {
        it.type == ControlType.BUTTON &&
            (it.buttonAimCancelOnBoundaryExit || it.buttonAimRearmOnBoundaryReturn)
    } } -> BUTTON_AIM_BOUNDARY_CANCEL_CONTROLLER_PROFILE_FORMAT_VERSION
    pages.any { page -> page.controls.any {
        it.excludeFromCameraFollow || it.cameraFollowStrengthPercent != 100f
    } } -> CAMERA_FOLLOW_CONTROLLER_PROFILE_FORMAT_VERSION
    pages.any { page -> page.controls.any {
        it.type == ControlType.BUTTON &&
            (it.buttonAimOutput == ButtonAimOutput.GAME_MOUSE ||
                it.buttonAimAlternateOutput == ButtonAimOutput.GAME_MOUSE)
    } } -> BUTTON_AIM_GAME_MOUSE_CONTROLLER_PROFILE_FORMAT_VERSION
    pages.any { page ->
        page.controls.any { it.type == ControlType.TOUCH_AIM && it.touchAimGameMouse }
    } -> GAME_MOUSE_CONTROLLER_PROFILE_FORMAT_VERSION
    else -> CONTROLLER_PROFILE_FORMAT_VERSION
}

data class ControllerPageOption(val id: String, val name: String)

data class PageTransitionResult(
    val changed: Boolean,
    val fromPageId: String,
    val toPageId: String,
    val warning: String? = null
)

/**
 * Runtime-only page navigation state. Active and previous page IDs are deliberately not serialized.
 */
class ControllerPageSession(
    homePageId: String,
    pageIds: Collection<String>
) {
    private var validPageIds: Set<String> = pageIds.toSet()
    var homePageId: String = homePageId
        private set
    var activePageId: String = homePageId
        private set
    var previousPageId: String? = null
        private set

    fun updateProfile(homePageId: String, pageIds: Collection<String>) {
        validPageIds = pageIds.toSet()
        this.homePageId = homePageId.takeIf(validPageIds::contains)
            ?: validPageIds.firstOrNull().orEmpty()
        if (activePageId !in validPageIds) activePageId = this.homePageId
        if (previousPageId !in validPageIds) previousPageId = null
    }

    fun resetToHome() {
        activePageId = homePageId
        previousPageId = null
    }

    /**
     * Runs cleanup before committing and displaying a real transition. Invalid targets and
     * already-active Go To actions are safe no-ops and do not release current output.
     */
    fun perform(
        action: PageAction,
        targetPageId: String,
        beforeSwitch: () -> Unit,
        showPage: (String) -> Unit
    ): PageTransitionResult {
        val from = activePageId
        val target = when (action) {
            PageAction.NONE -> return PageTransitionResult(false, from, from)
            PageAction.GO_TO -> targetPageId.validTargetOrWarning(from) ?: return missing(from)
            PageAction.TOGGLE -> {
                val requested = targetPageId.validTargetOrWarning(from) ?: return missing(from)
                if (requested != from) requested else previousPageId?.takeIf(validPageIds::contains)
                    ?: homePageId
            }
            PageAction.RETURN -> previousPageId?.takeIf(validPageIds::contains) ?: homePageId
            PageAction.HOME -> homePageId
        }

        if (target == from) return PageTransitionResult(false, from, from)

        beforeSwitch()
        previousPageId = from
        activePageId = target
        showPage(target)
        return PageTransitionResult(true, from, target)
    }

    private fun String.validTargetOrWarning(from: String): String? =
        takeIf { it.isNotBlank() && it in validPageIds }

    private fun missing(from: String) = PageTransitionResult(
        changed = false,
        fromPageId = from,
        toPageId = from,
        warning = "The target page is missing."
    )
}

fun newPageId(): String = UUID.randomUUID().toString()

fun normalizedPageName(name: String): String = name.trim().lowercase(Locale.ROOT)

fun ControllerProfile.page(pageId: String): ControllerPage? = pages.firstOrNull { it.id == pageId }

fun ControllerProfile.pageName(pageId: String): String? = page(pageId)?.name

fun ControllerProfile.hasPageName(name: String, exceptPageId: String? = null): Boolean {
    val normalized = normalizedPageName(name)
    return pages.any { it.id != exceptPageId && normalizedPageName(it.name) == normalized }
}

fun ControllerProfile.referenceCount(pageId: String, excludingPageId: String? = null): Int = pages.sumOf { page ->
    if (page.id == excludingPageId) return@sumOf 0
    page.controls.count { control ->
        control.isLocalPageAction() &&
            (control.pageAction == PageAction.GO_TO || control.pageAction == PageAction.TOGGLE) &&
            control.pageTargetId == pageId
    }
}

fun ControllerPage.hasUsablePageNavigation(validPageIds: Set<String>): Boolean = controls.any { control ->
    if (!control.isLocalPageAction()) return@any false
    when (control.pageAction) {
        PageAction.RETURN, PageAction.HOME -> true
        PageAction.TOGGLE -> control.pageTargetId in validPageIds
        else -> false
    }
}

fun Control.isLocalPageAction(): Boolean =
    type == ControlType.BUTTON && pageAction != PageAction.NONE

fun pageActionNeedsTarget(action: PageAction): Boolean =
    action == PageAction.GO_TO || action == PageAction.TOGGLE

/** Defense in depth: typed page actions must never escape through any receiver transport. */
fun isPageTransportCommand(value: String): Boolean {
    val command = value.uppercase(Locale.ROOT)
    return Regex("(?:^|[^A-Z0-9_])PAGE_[A-Z0-9_]*").containsMatchIn(command)
}
