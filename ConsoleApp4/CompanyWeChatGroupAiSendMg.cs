using System;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using Newtonsoft.Json;

public static class CompanyWeChatGroupAiSendMg
{
    // 替换为你的机器人Webhook地址
    private const string RobotUrl = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=a3d61999-6f8d-4b13-9156-fab5dafe9225";

    static async Task Main2()
    {
        // 推送文字消息
        var textMsg = new
        {
            msgtype = "text",
            text = new { content = "老婆，这是测试消息~" }
        };
        // await PushMessage(textMsg);
        await PushImage((GlobalUtil.GetQrFilePath("qr_cropped.jpg")));
        Console.ReadKey();
    }

   

    public static async  Task PushImage(string imagePath)
    {
        try
        {
            // 推送图片消息
            await PushImageMessage(imagePath);
            Console.WriteLine("图片推送请求已发送，请查看群聊！");
        }
        catch (Exception ex)
        {
            // 捕获异常，方便排查问题
            Console.WriteLine($"推送失败：{ex.Message}");
        }
    }


    /// <summary>
    /// 推送图片消息的核心方法
    /// </summary>
    static async Task PushImageMessage(string ImagePath)
    {
        // 1. 检查图片文件是否存在
        if (!File.Exists(ImagePath))
        {
            throw new FileNotFoundException("图片文件不存在，请检查路径是否正确", ImagePath);
        }

        // 2. 读取图片字节数组
        byte[] imageBytes = File.ReadAllBytes(ImagePath);

        // 3. 计算图片的Base64编码（企业微信必填）
        string imageBase64 = Convert.ToBase64String(imageBytes);

        // 4. 计算图片的MD5值（企业微信必填，小写）
        string imageMd5 = BitConverter.ToString(MD5.Create().ComputeHash(imageBytes))
                                      .Replace("-", "")
                                      .ToLower();

        // 5. 构造图片消息的JSON结构
        var imageMsg = new
        {
            msgtype = "image", // 消息类型固定为image
            image = new
            {
                base64 = imageBase64,
                md5 = imageMd5
            }
        };

        // 6. 调用通用推送方法发送消息
        await PushMessage(imageMsg);
    }


    /// <summary>
    /// 通用的消息推送方法（复用）
    /// </summary>
    static async Task PushMessage(object msg,int msg2=  2)
    {
        using (var client = new HttpClient())
        {
            // 设置超时时间，避免卡壳
            client.Timeout = TimeSpan.FromSeconds(10);

            // 序列化JSON（确保中文不转义）
            string json = JsonConvert.SerializeObject(msg, new JsonSerializerSettings
            {
                StringEscapeHandling = StringEscapeHandling.EscapeNonAscii
            });

            // 构造请求内容
            var content = new StringContent(json, Encoding.UTF8, "application/json");

            // 发送POST请求
            var response = await client.PostAsync(RobotUrl, content);

            // 打印响应结果，方便排查
            string responseContent = await response.Content.ReadAsStringAsync();
            Console.WriteLine($"接口响应：{responseContent}");
        }
    }
    /// <summary>
    /// 发送存文本测试 
    /// </summary>
    /// <param name="msg"></param>
    /// <returns></returns>
    static async Task PushMessage(object msg)
    {
        using (var client = new HttpClient())
        {
            var json = JsonConvert.SerializeObject(msg);
            var content = new StringContent(json, Encoding.UTF8, "application/json");
            var response = await client.PostAsync(RobotUrl, content);
            Console.WriteLine(await response.Content.ReadAsStringAsync());
        }
    }
}
