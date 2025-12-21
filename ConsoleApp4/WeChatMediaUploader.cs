using System;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

public static class WeChatMediaUploader
{
    /// <summary>
    /// 上传图片到微信，返回 media_id
    /// </summary>
    public static async Task<string> UploadImageAsync(byte[] imageBytes, string fileName)
    {
        // ⚠️ 建议兜底：图片太小微信会拒
        if (imageBytes.Length < 5 * 1024)
            throw new Exception("图片过小，微信可能不显示（<5KB）");

        string accessToken = await WeChatTokenHelper.GetAccessTokenAsync();

        string url =
        $"https://api.weixin.qq.com/cgi-bin/media/upload" +
        $"?access_token={accessToken}" +
        $"&type=image";

        // 经典 using 写法：通过大括号限定对象生命周期，自动释放资源
        using (var client = new HttpClient())
        {
            using (var content = new MultipartFormDataContent())
            {
                var imageContent = new ByteArrayContent(imageBytes);
                imageContent.Headers.ContentType =
                new MediaTypeHeaderValue("image/jpeg");

                content.Add(imageContent, "media", fileName);

                var resp = await client.PostAsync(url, content);
                var json = await resp.Content.ReadAsStringAsync();

                var jo = JObject.Parse(json);

                if (jo["errcode"] != null)
                    throw new Exception("微信上传失败：" + json);

                return jo["media_id"].ToString();
            } // content 在此处自动释放
        } // client 在此处自动释放
    }
}
