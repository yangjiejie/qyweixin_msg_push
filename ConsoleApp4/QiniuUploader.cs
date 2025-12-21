
using ConsoleApp4;
using Qiniu.Http;

using Qiniu.Storage;
using Qiniu.Util;
using System;
using System.IO;

public class QiniuUploadHelper
{
    /// <summary>
    /// 上传到七牛云  测试验证ok 
    /// </summary>
    /// <param name="localPath"></param>
    /// <param name="saveKey"></param>
    /// <returns></returns>
    /// <exception cref="FileNotFoundException"></exception>
    /// <exception cref="Exception"></exception>
    public static string UploadFile(string localPath, string saveKey = null)
    {
        // 0. 前置校验：文件是否存在
        if (!File.Exists(localPath))
            throw new FileNotFoundException("本地文件不存在", localPath);

        // 1. 处理保存键（默认用原文件名）
        saveKey = saveKey ?? Path.GetFileName(localPath);

        // 2. 生成上传凭证（1小时有效期）
        var mac = new Mac(AppConfig.qly_ak, AppConfig.qly_sk);
        var putPolicy = new PutPolicy
        {
            Scope = $"{AppConfig.qly_Bucket}:{saveKey}", // 格式：存储空间名:保存键
            
        };
        putPolicy.SetExpires(3600 * 24);
       
        string token = Auth.CreateUploadToken(mac, putPolicy.ToJsonString());

        // 3. 配置 UploadManager（关键修复：必须传 Config！）
        var config = new Config
        {
            // 选择上传区域（根据你的存储空间所在区域选，否则可能上传失败）
            Zone = AppConfig.qly_zone, // 华东区（默认，若你的空间在其他区需改：ZONE_CN_North=华北，ZONE_CN_South=华南，ZONE_US_North=北美）
            UseHttps = true, // 推荐用 HTTPS
            UseCdnDomains = true // 是否使用CDN加速上传（空间绑定了CDN域名时开启）
        };
        var um = new UploadManager(config); // 这里必须传 config！

        // 4. 执行上传
        HttpResult result = um.UploadFile(localPath, saveKey, token,null);

        // 5. 结果处理
        if (result.Code != 200)
            throw new Exception($"七牛上传失败 [Code:{result.Code}]：{result.Text}");

        /* ---------- 2. 生成私有下载链接 ---------- */
        // 60 min 有效期，可按需改
        int expiresInSeconds = 3600;
        string privateUrl = DownloadManager.CreatePrivateUrl(mac,
                                                             AppConfig.qly_Domain,
                                                             saveKey,
                                                             expiresInSeconds);
        return privateUrl;   // 形如 https://xxx.com/qrcode.jpg?e=1719xxx&token=xxx
    }
}