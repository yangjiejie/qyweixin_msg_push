using System;
using System.Net.Http;
using System.Threading.Tasks;
using ConsoleApp4;
using Newtonsoft.Json.Linq;

public static class WeChatTokenHelper
{
    private static string _accessToken;
    private static DateTime _expireTime;

    public static async Task<string> GetAccessTokenAsync()
    {
        if (!string.IsNullOrEmpty(_accessToken) && DateTime.Now < _expireTime)
            return _accessToken;

        string url =
            $"https://api.weixin.qq.com/cgi-bin/token" +
            $"?grant_type=client_credential" +
            $"&appid={AppConfig.wechat_appid}" +
            $"&secret={AppConfig.wechat_secret}";

        using (var client = new HttpClient())
        {
            var json = await client.GetStringAsync(url);
            var jo = JObject.Parse(json);
            _accessToken = jo["access_token"].ToString();
            int expiresIn = jo["expires_in"].Value<int>();

            _expireTime = DateTime.Now.AddSeconds(expiresIn - 300);
            return _accessToken;
        }
           

        
        
    }
}
