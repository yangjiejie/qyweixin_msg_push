using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Qiniu.Storage;

namespace ConsoleApp4
{
    public class AppConfig
    {
        //微信公众号 
        public const string wechat_appid = "wxceb916768f1d5710";
        public const string wechat_secret = "ee78ca054f074ee111e146ce9f9ece87";

        //plus plus token 
        public const string plusPlusKey = "1bc89367141b419faccced47188986c4";


        //图床 七牛云ak和sk
        public const string qly_ak = "FSZqzDTp5Q5tZo4FfooPiImHabR8dqs9pKFCh3Zp";
        public const string qly_sk = "s3HQuzqg3mE8OKWTRB2Ws5WSVeyrx8Sgmf1jepp1";
        //存储空间名  
        public const string qly_Bucket = "wuhanyangjie";
        //现在用的测试环境 可以试用30天 
        public const string qly_Domain = "t7lr41okt.hn-bkt.clouddn.com";
        //七牛云 地区 我在网页上勾选的是华南 
        public static Zone qly_zone = Zone.ZONE_CN_South; 
    }
}
