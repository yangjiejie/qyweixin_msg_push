using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Threading.Tasks;
using ConsoleApp4;

public static class PushPlusHelper
{
    // 经典 HttpClient 声明（静态单例复用，避免频繁创建销毁）
    private static readonly HttpClient _httpClient = new HttpClient();

    /// <summary>
    /// 推送本地图片到微信（通过 PushPlus + 七牛云）
    /// </summary>
    /// <param name="token">PushPlus 令牌</param>
    /// <param name="title">消息标题</param>
    /// <param name="localImagePath">本地图片路径</param>
    /// <param name="imgWidth">图片显示宽度（默认100%）</param>
    public static async Task PushImageAsync( string title, string localImagePath, string imgWidth = "100%")
    {
        // 空值校验
        if (string.IsNullOrWhiteSpace(AppConfig.plusPlusKey))
        {
            Console.WriteLine("[PushPlus] 未配置Token，跳过推送");
            return;
        }

        try
        {
            // 1. 上传到七牛云（复用你已有的封装）(备份而已)
            string imgUrl = QiniuUploadHelper.UploadFile(localImagePath);
            if (string.IsNullOrWhiteSpace(imgUrl))
            {
                throw new Exception("七牛云上传失败，返回空URL");
            }
            Console.WriteLine(imgUrl);
            //// 2. 构造HTML内容（带缓存戳，避免微信缓存旧图）
            //string cacheStamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString(); // 经典时间戳
            //string htmlContent = string.Format(
            //    "<img src=\"{0}?t={1}\" alt=\"图片\" style=\"max-width:{2};display:block;margin:0 auto;\"/>" +
            //    "<p style='color:#999;font-size:14px;text-align:center;'>点击图片可放大查看</p>",
            //    imgUrl, cacheStamp, imgWidth);

            //// 3. 构造推送表单
            //var formData = new Dictionary<string, string>();
            //formData.Add("token", AppConfig.plusPlusKey);
            //formData.Add("title", title);
            //formData.Add("content", htmlContent);
            //formData.Add("template", "html"); // 强制用HTML模板（避免Markdown兼容问题）

            //FormUrlEncodedContent formContent = new FormUrlEncodedContent(formData);

            //// 4. 发送POST请求（经典using嵌套）
            //using (HttpResponseMessage response = await _httpClient.PostAsync("https://www.pushplus.plus/send", formContent))
            //{
            //    using (HttpContent content = response.Content)
            //    {
            //        string result = await content.ReadAsStringAsync();
            //        Console.WriteLine("[PushPlus] 推送结果：" + result);
            //    }
            //}
        }
        catch (Exception ex)
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine("[PushPlus] 推送失败：" + ex.Message);
            Console.ResetColor();
        }
    }
}