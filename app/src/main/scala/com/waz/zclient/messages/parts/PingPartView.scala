/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
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
package com.waz.zclient.messages.parts

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.{LinearLayout, TextView}
import com.waz.threading.Threading
import com.waz.zclient.common.views.ChatheadView
import com.waz.zclient.messages.SyncEngineSignals.DisplayName.{Me, Other}
import com.waz.zclient.messages.{MessageViewPart, MsgPart, SyncEngineSignals}
import com.waz.zclient.ui.text.{GlyphTextView, TypefaceTextView}
import com.waz.zclient.ui.utils.TextViewUtils
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.{R, ViewHelper}

class PingPartView(context: Context, attrs: AttributeSet, style: Int) extends LinearLayout(context, attrs, style) with MessageViewPart with ViewHelper with EphemeralTextPart {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  override val tpe: MsgPart = MsgPart.Ping

  inflate(R.layout.message_ping_content)

  val textSizeRegular = context.getResources.getDimensionPixelSize(R.dimen.wire__text_size__regular)
  val textSizeEmoji = context.getResources.getDimensionPixelSize(R.dimen.wire__text_size__emoji)

  val chatheadView: ChatheadView        = findById(R.id.chathead)
  val textViewMessage: TypefaceTextView = findById(R.id.ttv__row_conversation__ping_message)
  val glyphTextView: GlyphTextView      = findById(R.id.gtv__ping_icon)

  val glyphTypeface = expired map { if (_) redactedTypeface else glyphTextView.getTypeface }

  val locale = context.getResources.getConfiguration.locale

  val signals = inject[SyncEngineSignals]

  val userName = signals.displayName(message)

  val text = userName map {
    case Me          => getString(R.string.content__you_pinged)
    case Other(name) => getString(R.string.content__xxx_pinged, name.toUpperCase(locale))
  }

  message.map(_.userId) { chatheadView.setUserId }

  (for {
    t <- text
    exp <- expired
  } yield (t, exp)).on(Threading.Ui) {
    case (t, true) =>
      textViewMessage.setText(t)
      //making the text bold ruins obfuscation for some reason
    case (t, _) =>
      textViewMessage.setText(t)
      TextViewUtils.boldText(textViewMessage)
  }

  signals.userAccentColor(message).on(Threading.Ui) { c =>
    textViewMessage.setTextColor(c.getColor())
    glyphTextView.setTextColor(c.getColor())
  }

  //TODO it would be nice if instead of overriding just one view, the EphemeralTextPart provided methods for achieving the same functionality on an arbitrary number of views...
  override val textView: TextView = textViewMessage
  glyphTypeface(glyphTextView.setTypeface)

  expired.map {
    case true => View.INVISIBLE
    case _ => View.VISIBLE
  }.on(Threading.Ui)(chatheadView.setVisibility)

  // TODO: animate new ping, we need some generic controller to track message animations acrosss recycled views
}
