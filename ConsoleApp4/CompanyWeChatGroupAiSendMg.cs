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
    private static string RobotUrl = "";

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
    /// 发送存文本测试 
    /// </summary>
    /// <param name="msg"></param>
    /// <returns></returns>
    static async Task PushMessage(object msg)
    {
        var path = Path.Combine( GlobalUtil.GetWorkDirPath(), "CompanyWeChatRobotWebHook.txt");
        if(!File.Exists(path))
        {
            Console.WriteLine("缺少配置文件CompanyWeChatRobotWebHook.txt，请找到你微信群的机器人webhook链接");
            return;
        }
        else
        {
            RobotUrl =  File.ReadAllText(path);
        }

        using (var client = new HttpClient())
        {
            var json = JsonConvert.SerializeObject(msg);
            var content = new StringContent(json, Encoding.UTF8, "application/json");
            var response = await client.PostAsync(RobotUrl, content);
            Console.WriteLine(await response.Content.ReadAsStringAsync());
        }
    }
}
