using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using ZXing;
using ZXing.Common;

namespace ConsoleApp4
{
    internal class Program
    {
        private static string _lastQrContent = string.Empty;
        [STAThread] // .NET Framework 下用 Windows Forms 功能推荐加这个
        static void Main(string[] args)
        {
            Console.WriteLine("=== .NET Framework 4.8 二维码监控工具 ===");
            Console.WriteLine("功能：每隔3秒截屏识别屏幕中的二维码");
            Console.WriteLine("按 Ctrl+C 结束程序\n");

            // 初始化 ZXing 二维码解码器（针对 QR Code 优化）
            var qrReader = new BarcodeReader
            {
                Options = new DecodingOptions
                {
                    PossibleFormats = new[] { BarcodeFormat.QR_CODE },
                    TryHarder = true, // 尝试更复杂的识别（提高准确率）
                    TryInverted = true // 识别反色二维码（黑底白码）
                }
            };

            // 循环监控
            while (true)
            {
                try
                {
                    // 1. 截取全屏
                    using (var screenShot = CaptureFullScreen())
                    {
                        // 2. 识别二维码
                        var result = qrReader.Decode(screenShot);

                        // 3. 处理识别结果
                        if (result != null && !string.IsNullOrWhiteSpace(result.Text))
                        {
                            string currentQr = result.Text.Trim();
                            if (currentQr != _lastQrContent)
                            {
                                _lastQrContent = currentQr;
                                ShowSuccessMessage(currentQr);
                            }
                            else
                            {
                                ShowNormalMessage("二维码内容未变化");
                            }
                        }
                        else
                        {
                            ShowNormalMessage("未检测到二维码");
                        }
                    }
                }
                catch (Exception ex)
                {
                    ShowErrorMessage($"程序异常：{ex.Message}");
                }

                // 间隔3秒
                System.Threading.Thread.Sleep(3000);
            }
        }

        /// <summary>
        /// 截取全屏
        /// </summary>
        private static Bitmap CaptureFullScreen()
        {
            // 获取主屏幕尺寸
            Screen primaryScreen = Screen.PrimaryScreen;
            Rectangle screenBounds = primaryScreen.Bounds;

            // 创建与屏幕尺寸一致的 Bitmap
            Bitmap screenBmp = new Bitmap(screenBounds.Width, screenBounds.Height);

            // 把屏幕内容绘制到 Bitmap
            using (Graphics g = Graphics.FromImage(screenBmp))
            {
                g.CopyFromScreen(
                    sourceX: screenBounds.X,
                    sourceY: screenBounds.Y,
                    destinationX: 0,
                    destinationY: 0,
                    blockRegionSize: screenBounds.Size
                );
            }

            return screenBmp;
        }

        #region 控制台输出辅助方法（带颜色区分）
        private static void ShowSuccessMessage(string content)
        {
            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] ✅ 检测到新二维码：");
            Console.WriteLine($"内容：{content}\n");
            Console.ResetColor();
        }

        private static void ShowNormalMessage(string message)
        {
            Console.ForegroundColor = ConsoleColor.Gray;
            Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] ℹ️ {message}");
            Console.ResetColor();
        }

        private static void ShowErrorMessage(string message)
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] ❌ {message}\n");
            Console.ResetColor();
        }
        #endregion
    }
}

