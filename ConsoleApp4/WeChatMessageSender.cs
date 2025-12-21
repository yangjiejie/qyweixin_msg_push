using Newtonsoft.Json;
using System;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;

public static class WeChatMessageSender
{
    public static async Task SendImageAsync(
        string accessToken,
        string openId,
        string mediaId)
    {
        string url =
            $"https://api.weixin.qq.com/cgi-bin/message/custom/send" +
            $"?access_token={accessToken}";

        var payload = new
        {
            touser = openId,
            msgtype = "image",
            image = new
            {
                media_id = mediaId
            }
        };

        string json = JsonConvert.SerializeObject(payload);

        // 经典using写法：用大括号限定HttpClient的生命周期
        using (var client = new HttpClient())
        {
            var content = new StringContent(json, Encoding.UTF8, "application/json");

            var resp = await client.PostAsync(url, content);
            string result = await resp.Content.ReadAsStringAsync();

            if (!resp.IsSuccessStatusCode || result.Contains("errcode"))
                throw new Exception("发送客服消息失败：" + result);
        } // client在此处自动释放资源
    }
}