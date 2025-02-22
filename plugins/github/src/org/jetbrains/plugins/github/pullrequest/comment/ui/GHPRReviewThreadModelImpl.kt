// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.github.pullrequest.comment.ui

import com.intellij.collaboration.ui.SimpleEventListener
import com.intellij.ui.CollectionListModel
import com.intellij.util.EventDispatcher
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestReviewThread

class GHPRReviewThreadModelImpl(thread: GHPullRequestReviewThread)
  : CollectionListModel<GHPRReviewCommentModel>(thread.comments.map {
  GHPRReviewCommentModel.convert(it, thread.startLine)
}), GHPRReviewThreadModel {

  override val id: String = thread.id
  override val createdAt = thread.createdAt
  override var state = thread.state
    private set
  override var isResolved: Boolean = thread.isResolved
    private set
  override var isOutdated: Boolean = thread.isOutdated
    private set
  override val commit = thread.originalCommit
  override val filePath = thread.path
  override val diffHunk = thread.diffHunk
  override val line = thread.line
  override val startLine = thread.startLine

  private val stateEventDispatcher = EventDispatcher.create(SimpleEventListener::class.java)

  init {
    maybeMarkFirstCommentResolved()
  }

  override fun update(thread: GHPullRequestReviewThread) {
    var dataChanged = false
    if (state != thread.state) {
      state = thread.state
      dataChanged = true
    }
    if (isResolved != thread.isResolved) {
      isResolved = thread.isResolved
      dataChanged = true
    }
    if (isOutdated != thread.isOutdated) {
      isOutdated = thread.isOutdated
      dataChanged = true
    }
    if (dataChanged) stateEventDispatcher.multicaster.eventOccurred()

    var removed = 0
    for (i in 0 until items.size) {
      val idx = i - removed
      val newComment = thread.comments.getOrNull(idx)
      if (newComment == null) {
        removeRange(idx, items.size - 1)
        break
      }

      val comment = items.getOrNull(idx) ?: break
      if (comment.id == newComment.id) {
        if (comment.update(newComment))
          fireContentsChanged(this, idx, idx)
      }
      else {
        remove(idx)
        removed++
      }
    }

    val newComments = thread.comments.subList(size, thread.comments.size)
    add(newComments.map { GHPRReviewCommentModel.convert(it, thread.startLine) })
    maybeMarkFirstCommentResolved()
  }

  private fun maybeMarkFirstCommentResolved() {
    if (size > 0) {
      getElementAt(0).isFirstInResolvedThread = isResolved
      for (i in 1 until size) {
        getElementAt(i).isFirstInResolvedThread = false
      }
    }
  }

  override fun addComment(comment: GHPRReviewCommentModel) {
    add(comment)
  }

  override fun addAndInvokeStateChangeListener(listener: () -> Unit) =
    SimpleEventListener.addAndInvokeListener(stateEventDispatcher, listener)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is GHPRReviewThreadModelImpl) return false

    if (id != other.id) return false

    return true
  }

  override fun hashCode(): Int {
    return id.hashCode()
  }
}