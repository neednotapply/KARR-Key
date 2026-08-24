package com.karr.key.auto

import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager

/**
 * The host's cap on how many rows a given template accepts. Car App Library templates reject a
 * list/pane built with more rows than this (the template fails to render on the head unit), and the
 * limit varies by car, so it must be read at runtime rather than hard-coded. See
 * [ConstraintManager.CONTENT_LIMIT_TYPE_LIST] / [ConstraintManager.CONTENT_LIMIT_TYPE_PANE].
 */
internal fun CarContext.contentLimit(limitType: Int): Int =
    getCarService(ConstraintManager::class.java).getContentLimit(limitType)
