using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using ZXing;
using ZXing.Common;

using System.Net.Http;
using System.Web;
using System.IO;
using System.Drawing.Imaging;
using SkiaSharp;

namespace ConsoleApp4
{
    public enum PushEnum
    {
        ServerChan,
        PlusPlus,
    }
    internal class Program
    {
        public static string _lastQrContent = string.Empty;




        

           [STAThread] // .NET Framework 下用 Windows Forms 功能推荐加这个
        static void Main(string[] args)
        {
            Console.WriteLine("=== .NET Framework 4.8 二维码监控工具 ===");
            Console.WriteLine("功能：每隔6秒截屏识别屏幕中的二维码");
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


                                // 1. 计算二维码矩形
                                if (result?.ResultPoints == null || result.ResultPoints.Length < 3) return;

                                float minX = result.ResultPoints.Min(p => p.X);
                                float maxX = result.ResultPoints.Max(p => p.X);
                                float minY = result.ResultPoints.Min(p => p.Y);
                                float maxY = result.ResultPoints.Max(p => p.Y);
                                int expandPixel = 30; // 这里需要考虑一定的阈值，不然截出来的二维码可能边缘不全 
                                int x = Math.Max(0, (int)minX - expandPixel);
                                int y = Math.Max(0, (int)minY -  expandPixel);
                                int w = Math.Min(screenShot.Width - x, (int)(maxX - minX) + 2 * expandPixel);
                                int h = Math.Min(screenShot.Height - y, (int)(maxY - minY) + 2 * expandPixel);

                                // 2. System.Drawing.Bitmap -> SKBitmap
                                SKBitmap fullBmp = null;
                                MemoryStream ms = new MemoryStream();
                                try
                                {
                                    //不能直接把 ImageFormat.Png 改成 ImageFormat.Jpeg 就完事，原因有两点：
                                    //JPG 不支持透明通道如果原始截图里带有透明区域（比如 WPF 窗口、分层窗口），System.Drawing 在保存时会抛 ArgumentException：“A generic error occurred in GDI+”。
                                    //JPG 质量参数  只是格式枚举，不能指定压缩质量；默认质量往往只有 75，且不可控


                                    screenShot.Save(ms, ImageFormat.Png);
                                    ms.Position = 0;
                                    fullBmp = SKBitmap.Decode(ms);
                                }
                                finally
                                {
                                    ms.Dispose();
                                }

                                // 3. 裁剪二维码区域
                                SKBitmap qrBmp = new SKBitmap(w, h);
                                SKCanvas canvas = null;
                                try
                                {
                                    canvas = new SKCanvas(qrBmp);
                                    canvas.DrawBitmap(fullBmp,
                                        new SKRect(x, y, x + w, y + h),
                                        new SKRect(0, 0, w, h));
                                }
                                finally
                                {
                                    if (canvas != null) canvas.Dispose();
                                }

                                // 4. 缩放
                                float ratio = 900f / w;
                                int newW = (int)(w * ratio);
                                int newH = (int)(h * ratio);
                                SKBitmap resized = null;
                                try
                                {
                                    resized = qrBmp.Resize(new SKImageInfo(newW, newH), SKFilterQuality.High);
                                }
                                finally
                                {
                                    qrBmp.Dispose();
                                    fullBmp.Dispose();
                                }

                                // 5. 编码成 JPG
                                SKImage qrImage = null;

                                try
                                {
                                    qrImage = SKImage.FromBitmap(resized);

                                    //获取二维码截图地址 
                                    string qrPath = GlobalUtil.GetQrFilePath("qr_cropped.jpg");
                                    using (var fs = new FileStream(qrPath, FileMode.Create, FileAccess.Write))
                                    {
                                        qrImage.Encode(SKEncodedImageFormat.Jpeg, 80).SaveTo(fs);
                                    }
                                    Console.WriteLine($"开始推送给企业微信{qrPath}");
                                    _= CompanyWeChatGroupAiSendMg.PushImage(qrPath);

                                 
                                }
                                finally
                                {
                                    if (qrImage != null) qrImage.Dispose();
                                    if (resized != null) resized.Dispose();

                                }

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
                System.Threading.Thread.Sleep(6000);
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

