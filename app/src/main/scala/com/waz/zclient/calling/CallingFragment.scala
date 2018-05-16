/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.calling

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.{Color, Matrix}
import android.os.Bundle
import android.support.v7.widget.{CardView, GridLayout}
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{FrameLayout, ImageView, TextView}
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.service.call.Avs.VideoState
import com.waz.avs.{VideoPreview, VideoRenderer}
import com.waz.model.{Dim2, UserId}
import com.waz.utils.events.Signal
import com.waz.utils.returning
import com.waz.zclient.calling.controllers.CallController
import com.waz.zclient.common.views.BackgroundDrawable
import com.waz.zclient.common.views.ImageController.{ImageSource, WireImage}
import com.waz.zclient.ui.utils.ColorUtils
import com.waz.zclient.utils.RichView
import com.waz.zclient.{FragmentHelper, R, ViewHelper}

class VideoPreview2(context: Context) extends VideoPreview(context) {

  private val aspectRatio = 1.3333334F
  private val orientation = 90

  override def onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int): Unit = {
    setMeasuredDimension(View.getDefaultSize(getSuggestedMinimumWidth, widthMeasureSpec),
      View.getDefaultSize(getSuggestedMinimumHeight, heightMeasureSpec))
  }

  setOpaque(false)

  override def onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int): Unit = {
    super.onLayout(changed, l, t, r, b)
    val m = new Matrix

    val vWidth = (r - l).toFloat
    val vHeight = (b - t).toFloat

    val vAspRatio = vWidth.toFloat / vHeight.toFloat

    val tAspRatio = if (orientation % 180 == 0) this.aspectRatio else 1.0F / this.aspectRatio

    val scaleX = Math.max(1f, tAspRatio / vAspRatio)
    val scaleY = Math.max(1f, vAspRatio / tAspRatio)

    val dx = - (scaleX * vWidth - vWidth) / 2
    val dy = - (scaleY * vHeight - vHeight) / 2

    m.postTranslate(dx, dy)
    m.setScale(scaleX, scaleY)
    setTransform(m)
  }
}

abstract class UserVideoView(context: Context, val userId: UserId) extends FrameLayout(context, null, 0) with ViewHelper {
  protected lazy val controller: CallController = inject[CallController]

  private val blackLevel = 0.58f

  inflate(R.layout.video_call_paused_view)

  private val pictureId: Signal[ImageSource] = for {
    z <- controller.callingZms
    Some(picture) <- z.users.userSignal(userId).map(_.picture)
  } yield WireImage(picture)

  protected val imageView = findById[ImageView](R.id.image_view)
  imageView.setBackground(new BackgroundDrawable(pictureId, getContext, Dim2(getWidth, getHeight)))
  imageView.setImageDrawable(new ColorDrawable(ColorUtils.injectAlpha(blackLevel, Color.BLACK)))

  protected val pausedText = findById[TextView](R.id.paused_text_view)

  addView(returning(videoView)(_.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))))

  controller.allVideoReceiveState.map(_.get(userId)).collect {
    case Some(VideoState.Started) => true
    case Some(VideoState.Paused) => false
  }.onUi { hasVideo =>
    pausedText.setVisible(!hasVideo)
    imageView.setVisible(!hasVideo)
    videoView.setVisible(hasVideo)
  }

  override def onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int): Unit = {
    super.onLayout(changed, left, top, right, bottom)
    if (changed) imageView.setBackground(new BackgroundDrawable(pictureId, getContext, Dim2(right - left, bottom - top)))
  }

  val videoView: View
}

class SelfVideoView(context: Context, userId: UserId) extends UserVideoView(context, userId) {
  pausedText.setText(R.string.empty_string)
  override lazy val videoView: View = returning(new VideoPreview2(getContext)) { v =>
    controller.setVideoPreview(Some(v))
  }
}

class OtherVideoView(context: Context, userId: UserId) extends UserVideoView(context, userId) {
  override lazy val videoView: View = new VideoRenderer(getContext, userId.str, false)
}

class CallingFragment extends FragmentHelper {

  private lazy val controlsFragment = ControlsFragment.newInstance

  private lazy val controller = inject[CallController]
  private lazy val previewCardView = view[CardView](R.id.preview_card_view)

  private var viewMap = Map[UserId, UserVideoView]()

  private lazy val videoGrid = returning(view[GridLayout](R.id.video_grid)) { vh =>
    Signal(controller.allVideoReceiveState, controller.callingZms.map(_.selfUserId)).onUi { case (vrs, selfId) =>

      def createView(userId: UserId): UserVideoView = returning {
        if (controller.callingZms.currentValue.map(_.selfUserId).contains(userId))
          new SelfVideoView(getContext, userId)
        else
          new OtherVideoView(getContext, userId)
      } { v =>
        viewMap = viewMap.updated(userId, v)
      }

      val isVideoBeingSent = vrs.get(selfId).contains(VideoState.Started)

      vh.foreach { v =>
        val views = vrs.toSeq.collect {
          case (userId, VideoState.Started | VideoState.Paused | VideoState.BadConnection) => userId
        }.map { uId => viewMap.getOrElse(uId, createView(uId))}

        v.removeAllViews()

        viewMap.get(selfId).foreach { selfView =>
          previewCardView.foreach { cardView =>
            if (views.size == 2 && isVideoBeingSent) {
              verbose("Showing card preview")
              cardView.removeAllViews()
              v.removeView(selfView)
              selfView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
              cardView.addView(selfView)
              cardView.setVisibility(View.VISIBLE)
            } else {
              verbose("Hiding card preview")
              cardView.removeAllViews()
              cardView.setVisibility(View.GONE)
            }
          }
        }

        val gridViews = views.filter {
          case _:SelfVideoView if views.size == 2 && isVideoBeingSent => false
          case _:SelfVideoView if views.size > 1 && !isVideoBeingSent => false
          case _ => true
        }.sortWith {
          case (_:SelfVideoView, _) => true
          case (v1, v2) => v1.userId.str.hashCode > v2.userId.str.hashCode
        }

        gridViews.zipWithIndex.foreach { case (r, index) =>
          val (row, col, span) = index match {
            case 0 if !isVideoBeingSent && gridViews.size == 2 => (0, 0, 2)
            case 0 => (0, 0, 1)
            case 1 if !isVideoBeingSent && gridViews.size == 2 => (1, 0, 2)
            case 1 => (0, 1, 1)
            case 2 if gridViews.size == 3 => (1, 0, 2)
            case 2 => (1, 0, 1)
            case 3 => (1, 1, 1)
          }
          verbose(s"Span sizes: ($row, $col, $span)")
          val params = new GridLayout.LayoutParams()
          params.width = 0
          params.height = 0
          params.rowSpec = GridLayout.spec(row, 1, GridLayout.FILL, 1f)
          params.columnSpec = GridLayout.spec(col, span, GridLayout.FILL, 1f)
          r.setLayoutParams(params)

          if (r.getParent == null)
            v.addView(r)
        }
        //TODO: ask AVS why VideoRenderer stops playing video after resizing view
/*
        val viewsToRemove = viewMap.filter {
          case (uid, selfView) if uid == selfId => !gridViews.contains(selfView)
          case (uId, _) => !vrs.keys.toSet.contains(uId)
        }
        viewsToRemove.foreach { case (_, view) =>
          v.removeView(view)
        }
        viewMap = viewMap.filter { case (uId, _) => vrs.keys.toSet.contains(uId) }
        */
      }
    }
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle) =
    inflater.inflate(R.layout.fragment_calling_outer, container, false)

  override def onViewCreated(view: View, savedInstanceState: Bundle) = {
    super.onViewCreated(view, savedInstanceState)

    videoGrid

    getChildFragmentManager.beginTransaction
      .replace(R.id.controls_layout, controlsFragment, ControlsFragment.Tag)
      .commit
  }

  override def onBackPressed() = {
    withFragmentOpt(R.id.controls_layout) {
      case Some(f: FragmentHelper) if f.onBackPressed()               => true
      case Some(_) if getChildFragmentManager.popBackStackImmediate() => true
      case _ => super.onBackPressed()
    }
  }

  override def onDestroyView(): Unit = {
    super.onDestroyView()
    viewMap = Map()
  }
}

object CallingFragment {
  val Tag = implicitLogTag
  def apply(): CallingFragment = new CallingFragment()
}
