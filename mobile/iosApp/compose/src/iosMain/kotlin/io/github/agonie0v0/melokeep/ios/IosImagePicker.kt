@file:OptIn(ExperimentalForeignApi::class)

package io.github.agonie0v0.melokeep.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerEditedImage
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.memcpy

object IosImagePicker {
	internal var rootHostViewController: UIViewController? = null

	// Strong reference while the picker is presented so Obj-C delegate is not collected.
	private var activeDelegate: NSObject? = null

	fun pickImageAsDataUrl(
		onResult: (dataUrl: String?) -> Unit,
		onError: (String) -> Unit = {},
	) {
		val presenter = findTopViewController()
		if (presenter == null) {
			onError("无法获取当前页面控制器")
			return
		}
		if (!UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary)) {
			onError("当前设备相册不可用")
			return
		}

		val picker = UIImagePickerController().apply {
			sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
			allowsEditing = false
		}

		val delegate = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
			override fun imagePickerController(
				picker: UIImagePickerController,
				didFinishPickingMediaWithInfo: Map<Any?, *>,
			) {
				val rawImage = (didFinishPickingMediaWithInfo[UIImagePickerControllerEditedImage] as? UIImage)
					?: (didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage)
				picker.dismissViewControllerAnimated(true) {
					activeDelegate = null
					if (rawImage == null) {
						onResult(null)
						return@dismissViewControllerAnimated
					}
					val dataUrl = encodeImageToDataUrl(rawImage)
					if (dataUrl != null) {
						onResult(dataUrl)
					} else {
						onError("图片编码失败")
					}
				}
			}

			override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
				picker.dismissViewControllerAnimated(true) {
					activeDelegate = null
					onResult(null)
				}
			}
		}

		activeDelegate = delegate
		picker.delegate = delegate
		presenter.presentViewController(picker, animated = true, completion = null)
	}

	private fun encodeImageToDataUrl(image: UIImage): String? {
		val (origW, origH) = image.size.useContents { width to height }
		if (origW <= 0.0 || origH <= 0.0) return null

		val maxSide = 1280.0
		val ratio = if (origW > maxSide || origH > maxSide) {
			maxSide / maxOf(origW, origH)
		} else {
			1.0
		}
		val targetW = (origW * ratio).coerceAtLeast(1.0)
		val targetH = (origH * ratio).coerceAtLeast(1.0)

		UIGraphicsBeginImageContextWithOptions(CGSizeMake(targetW, targetH), false, 1.0)
		image.drawInRect(CGRectMake(0.0, 0.0, targetW, targetH))
		val resized = UIGraphicsGetImageFromCurrentImageContext() ?: image
		UIGraphicsEndImageContext()

		val jpegData = UIImageJPEGRepresentation(resized, 0.78) ?: return null
		val bytes = jpegData.toByteArray()
		if (bytes.isEmpty()) return null
		val base64 = encodeBase64(bytes)
		if (base64.isEmpty()) return null
		return "data:image/jpeg;base64,$base64"
	}

	private fun NSData.toByteArray(): ByteArray {
		val size = length.toInt()
		if (size <= 0) return ByteArray(0)
		val out = ByteArray(size)
		val ptr = bytes ?: return ByteArray(0)
		out.usePinned { pinned ->
			memcpy(pinned.addressOf(0), ptr, length)
		}
		return out
	}

	private fun encodeBase64(input: ByteArray): String {
		if (input.isEmpty()) return ""
		val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
		val out = StringBuilder(((input.size + 2) / 3) * 4)
		var i = 0
		while (i < input.size) {
			val b0 = input[i].toInt() and 0xFF
			val b1 = if (i + 1 < input.size) input[i + 1].toInt() and 0xFF else -1
			val b2 = if (i + 2 < input.size) input[i + 2].toInt() and 0xFF else -1

			out.append(alphabet[b0 ushr 2])
			if (b1 >= 0) {
				out.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
				if (b2 >= 0) {
					out.append(alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
					out.append(alphabet[b2 and 0x3F])
				} else {
					out.append(alphabet[(b1 and 0x0F) shl 2])
					out.append('=')
				}
			} else {
				out.append(alphabet[(b0 and 0x03) shl 4])
				out.append("==")
			}
			i += 3
		}
		return out.toString()
	}

	private fun findTopViewController(): UIViewController? {
		var current = rootHostViewController ?: UIApplication.sharedApplication.keyWindow?.rootViewController
			?: return null
		while (current.presentedViewController != null) {
			current = current.presentedViewController!!
		}
		return current
	}
}
