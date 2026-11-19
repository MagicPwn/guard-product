export type EnglishWord = readonly [word: string, meaning: string];

export interface MathProblem {
  prompt: string;
  answer: string;
}

export interface ChineseCharacter {
  char: string;
  pinyin: string;
  word: string;
}

export interface ChinesePoem {
  title: string;
  author: string;
  lines: readonly string[];
}

export const ENGLISH_UNITS: Record<number, { 1: readonly EnglishWord[]; 2: readonly EnglishWord[] }> = {
  1: {
    1: [["hello", "你好"], ["hi", "嗨"], ["bye", "再见"], ["name", "名字"], ["boy", "男孩"], ["girl", "女孩"]],
    2: [["red", "红色"], ["blue", "蓝色"], ["green", "绿色"], ["yellow", "黄色"], ["black", "黑色"], ["white", "白色"]]
  },
  2: {
    1: [["one", "一"], ["two", "二"], ["three", "三"], ["four", "四"], ["five", "五"], ["six", "六"]],
    2: [["book", "书"], ["pen", "钢笔"], ["ruler", "尺子"], ["bag", "书包"], ["pencil", "铅笔"], ["eraser", "橡皮"]]
  },
  3: {
    1: [["apple", "苹果"], ["banana", "香蕉"], ["orange", "橙子"], ["pear", "梨"], ["grape", "葡萄"], ["cat", "猫"]],
    2: [["dog", "狗"], ["bird", "鸟"], ["fish", "鱼"], ["panda", "熊猫"], ["monkey", "猴子"], ["tiger", "老虎"]]
  },
  4: {
    1: [["classroom", "教室"], ["window", "窗户"], ["blackboard", "黑板"], ["desk", "书桌"], ["chair", "椅子"], ["light", "灯"]],
    2: [["morning", "早晨"], ["afternoon", "下午"], ["evening", "晚上"], ["breakfast", "早餐"], ["lunch", "午餐"], ["dinner", "晚餐"]]
  },
  5: {
    1: [["teacher", "老师"], ["doctor", "医生"], ["farmer", "农民"], ["driver", "司机"], ["nurse", "护士"], ["cook", "厨师"]],
    2: [["spring", "春天"], ["summer", "夏天"], ["autumn", "秋天"], ["winter", "冬天"], ["weather", "天气"], ["season", "季节"]]
  },
  6: {
    1: [["museum", "博物馆"], ["hospital", "医院"], ["cinema", "电影院"], ["library", "图书馆"], ["bookstore", "书店"], ["restaurant", "餐馆"]],
    2: [["bicycle", "自行车"], ["subway", "地铁"], ["train", "火车"], ["plane", "飞机"], ["ship", "轮船"], ["bus", "公交车"]]
  }
};

export const MATH_CALCULATIONS: Record<number, { 1: readonly MathProblem[]; 2: readonly MathProblem[] }> = {
  1: {
    1: [
      { prompt: "3 + 5 = ?", answer: "8" }, { prompt: "9 - 4 = ?", answer: "5" },
      { prompt: "7 + 6 = ?", answer: "13" }, { prompt: "12 - 5 = ?", answer: "7" },
      { prompt: "8 + 7 = ?", answer: "15" }, { prompt: "15 - 6 = ?", answer: "9" }
    ],
    2: [
      { prompt: "10 + 4 = ?", answer: "14" }, { prompt: "18 - 9 = ?", answer: "9" },
      { prompt: "6 + 9 = ?", answer: "15" }, { prompt: "17 - 8 = ?", answer: "9" },
      { prompt: "5 + 8 = ?", answer: "13" }, { prompt: "14 - 7 = ?", answer: "7" }
    ]
  },
  2: {
    1: [
      { prompt: "24 + 37 = ?", answer: "61" }, { prompt: "82 - 45 = ?", answer: "37" },
      { prompt: "6 × 7 = ?", answer: "42" }, { prompt: "48 ÷ 6 = ?", answer: "8" },
      { prompt: "35 + 28 = ?", answer: "63" }, { prompt: "90 - 36 = ?", answer: "54" }
    ],
    2: [
      { prompt: "7 × 8 = ?", answer: "56" }, { prompt: "72 ÷ 9 = ?", answer: "8" },
      { prompt: "46 + 39 = ?", answer: "85" }, { prompt: "100 - 58 = ?", answer: "42" },
      { prompt: "5 × 9 = ?", answer: "45" }, { prompt: "81 ÷ 9 = ?", answer: "9" }
    ]
  },
  3: {
    1: [
      { prompt: "23 × 4 = ?", answer: "92" }, { prompt: "96 ÷ 8 = ?", answer: "12" },
      { prompt: "125 + 238 = ?", answer: "363" }, { prompt: "600 - 247 = ?", answer: "353" },
      { prompt: "(18 + 12) × 3 = ?", answer: "90" }, { prompt: "144 ÷ 12 = ?", answer: "12" }
    ],
    2: [
      { prompt: "25 × 4 = ?", answer: "100" }, { prompt: "7 × 8 + 16 = ?", answer: "72" },
      { prompt: "345 + 278 = ?", answer: "623" }, { prompt: "800 - 456 = ?", answer: "344" },
      { prompt: "19 × 5 = ?", answer: "95" }, { prompt: "168 ÷ 8 = ?", answer: "21" }
    ]
  },
  4: {
    1: [
      { prompt: "125 × 12 = ?", answer: "1500" }, { prompt: "936 ÷ 6 = ?", answer: "156" },
      { prompt: "2450 + 1375 = ?", answer: "3825" }, { prompt: "5000 - 2386 = ?", answer: "2614" },
      { prompt: "48 × (25 - 17) = ?", answer: "384" }, { prompt: "720 ÷ 8 ÷ 9 = ?", answer: "10" }
    ],
    2: [
      { prompt: "36 × 15 = ?", answer: "540" }, { prompt: "1024 ÷ 16 = ?", answer: "64" },
      { prompt: "2345 + 1876 = ?", answer: "4221" }, { prompt: "7000 - 3456 = ?", answer: "3544" },
      { prompt: "64 × (30 - 22) = ?", answer: "512" }, { prompt: "960 ÷ 12 ÷ 4 = ?", answer: "20" }
    ]
  },
  5: {
    1: [
      { prompt: "1.5 + 2.75 = ?", answer: "4.25" }, { prompt: "6.4 - 2.15 = ?", answer: "4.25" },
      { prompt: "0.8 × 5 = ?", answer: "4" }, { prompt: "12.6 ÷ 3 = ?", answer: "4.2" },
      { prompt: "2.5 × 4.2 = ?", answer: "10.5" }, { prompt: "1 - 0.36 = ?", answer: "0.64" }
    ],
    2: [
      { prompt: "3.75 + 6.25 = ?", answer: "10" }, { prompt: "9.6 ÷ 0.8 = ?", answer: "12" },
      { prompt: "0.25 × 8 = ?", answer: "2" }, { prompt: "15.3 - 7.8 = ?", answer: "7.5" },
      { prompt: "4.5 × 2 = ?", answer: "9" }, { prompt: "18 ÷ 1.5 = ?", answer: "12" }
    ]
  },
  6: {
    1: [
      { prompt: "25% × 80 = ?", answer: "20" }, { prompt: "3/4 × 60 = ?", answer: "45" },
      { prompt: "0.75 写成百分数是多少？（只填数字）", answer: "75" },
      { prompt: "0.25 + 0.25 = ?", answer: "0.5" }, { prompt: "120 × 0.15 = ?", answer: "18" },
      { prompt: "45 ÷ 0.9 = ?", answer: "50" }
    ],
    2: [
      { prompt: "15% × 200 = ?", answer: "30" }, { prompt: "4/5 × 25 = ?", answer: "20" },
      { prompt: "7.2 ÷ 0.8 = ?", answer: "9" }, { prompt: "1.25 × 4 = ?", answer: "5" },
      { prompt: "80% × 90 = ?", answer: "72" }, { prompt: "2/3 × 36 = ?", answer: "24" }
    ]
  }
};

export const MATH_APPLICATIONS: Record<number, { 1: readonly MathProblem[]; 2: readonly MathProblem[] }> = {
  1: {
    1: [
      { prompt: "小明有 3 个苹果，妈妈又给了他 5 个，一共有几个？", answer: "8" },
      { prompt: "树上有 9 只鸟，飞走 4 只，还剩几只？", answer: "5" },
      { prompt: "一盒铅笔有 6 支，两盒一共有几支？", answer: "12" }
    ],
    2: [
      { prompt: "小红有 12 颗糖，吃了 5 颗，还剩几颗？", answer: "7" },
      { prompt: "停车场有 7 辆车，又来了 8 辆，现在有几辆？", answer: "15" },
      { prompt: "有 10 个气球，破了 3 个，还剩几个？", answer: "7" }
    ]
  },
  2: {
    1: [
      { prompt: "书架上有 24 本书，又放入 37 本，现在共有多少本？", answer: "61" },
      { prompt: "操场上有 45 人，走了 18 人，还剩多少人？", answer: "27" },
      { prompt: "每排种 6 棵树，7 排一共种多少棵？", answer: "42" }
    ],
    2: [
      { prompt: "48 块饼干平均分给 6 个小朋友，每人分几块？", answer: "8" },
      { prompt: "每盒装 8 个鸡蛋，3 盒一共有多少个？", answer: "24" },
      { prompt: "有 100 元，买东西花了 46 元，还剩多少元？", answer: "54" }
    ]
  },
  3: {
    1: [
      { prompt: "每箱有 23 瓶水，4 箱一共有多少瓶？", answer: "92" },
      { prompt: "96 名学生平均分成 8 队，每队多少人？", answer: "12" },
      { prompt: "上午卖出 125 本书，下午卖出 238 本，一共卖出多少本？", answer: "363" }
    ],
    2: [
      { prompt: "仓库有 600 箱货物，运走 247 箱，还剩多少箱？", answer: "353" },
      { prompt: "每张桌子坐 6 人，18 张桌子能坐多少人？", answer: "108" },
      { prompt: "144 个苹果平均装进 12 个箱子，每箱多少个？", answer: "12" }
    ]
  },
  4: {
    1: [
      { prompt: "每盒有 125 支笔，12 盒一共有多少支？", answer: "1500" },
      { prompt: "936 本书平均分给 6 个班，每班多少本？", answer: "156" },
      { prompt: "第一天读了 2450 字，第二天读了 1375 字，两天共读多少字？", answer: "3825" }
    ],
    2: [
      { prompt: "一台机器每分钟加工 36 个零件，15 分钟加工多少个？", answer: "540" },
      { prompt: "1024 名同学平均站成 16 列，每列多少人？", answer: "64" },
      { prompt: "学校有 7000 本图书，借出 3456 本，还剩多少本？", answer: "3544" }
    ]
  },
  5: {
    1: [
      { prompt: "一支笔 1.5 元，一块橡皮 2.75 元，一共多少元？", answer: "4.25" },
      { prompt: "一瓶水 6.4 元，优惠 2.15 元，现在多少元？", answer: "4.25" },
      { prompt: "每千克苹果 0.8 元，买 5 千克需要多少元？", answer: "4" }
    ],
    2: [
      { prompt: "12.6 米长的绳子平均剪成 3 段，每段多少米？", answer: "4.2" },
      { prompt: "长方形长 4.2 米、宽 2.5 米，面积是多少平方米？", answer: "10.5" },
      { prompt: "一根绳子长 1 米，用去 0.36 米，还剩多少米？", answer: "0.64" }
    ]
  },
  6: {
    1: [
      { prompt: "全校 80 名学生中有 25% 参加合唱队，参加的有多少人？", answer: "20" },
      { prompt: "一本书共 60 页，已经读了 3/4，读了多少页？", answer: "45" },
      { prompt: "一件商品原价 120 元，打八五折，现价多少元？", answer: "102" }
    ],
    2: [
      { prompt: "男生与女生人数比是 2:3，共 30 人，男生有多少人？", answer: "12" },
      { prompt: "200 千克粮食中有 15% 是小米，小米有多少千克？", answer: "30" },
      { prompt: "一本书的 4/5 是 20 页，这本书共有多少页？", answer: "25" }
    ]
  }
};

export const CHINESE_UNITS: Record<number, { 1: readonly ChineseCharacter[]; 2: readonly ChineseCharacter[] }> = {
  1: {
    1: [
      { char: "天", pinyin: "tiān", word: "天空" }, { char: "地", pinyin: "dì", word: "大地" },
      { char: "人", pinyin: "rén", word: "人民" }, { char: "你", pinyin: "nǐ", word: "你好" },
      { char: "我", pinyin: "wǒ", word: "我们" }, { char: "他", pinyin: "tā", word: "他们" }
    ],
    2: [
      { char: "一", pinyin: "yī", word: "一个" }, { char: "二", pinyin: "èr", word: "二月" },
      { char: "三", pinyin: "sān", word: "三天" }, { char: "上", pinyin: "shàng", word: "上学" },
      { char: "口", pinyin: "kǒu", word: "开口" }, { char: "目", pinyin: "mù", word: "目光" }
    ]
  },
  2: {
    1: [
      { char: "春", pinyin: "chūn", word: "春天" }, { char: "风", pinyin: "fēng", word: "风筝" },
      { char: "花", pinyin: "huā", word: "花朵" }, { char: "草", pinyin: "cǎo", word: "草地" },
      { char: "树", pinyin: "shù", word: "树木" }, { char: "叶", pinyin: "yè", word: "树叶" }
    ],
    2: [
      { char: "山", pinyin: "shān", word: "高山" }, { char: "水", pinyin: "shuǐ", word: "河水" },
      { char: "石", pinyin: "shí", word: "石头" }, { char: "田", pinyin: "tián", word: "田地" },
      { char: "土", pinyin: "tǔ", word: "土地" }, { char: "木", pinyin: "mù", word: "木头" }
    ]
  },
  3: {
    1: [
      { char: "晨", pinyin: "chén", word: "早晨" }, { char: "绒", pinyin: "róng", word: "绒花" },
      { char: "汉", pinyin: "hàn", word: "汉字" }, { char: "艳", pinyin: "yàn", word: "鲜艳" },
      { char: "服", pinyin: "fú", word: "衣服" }, { char: "装", pinyin: "zhuāng", word: "服装" }
    ],
    2: [
      { char: "铺", pinyin: "pū", word: "铺路" }, { char: "洼", pinyin: "wā", word: "水洼" },
      { char: "印", pinyin: "yìn", word: "脚印" }, { char: "排", pinyin: "pái", word: "排队" },
      { char: "列", pinyin: "liè", word: "排列" }, { char: "规", pinyin: "guī", word: "规则" }
    ]
  },
  4: {
    1: [
      { char: "潮", pinyin: "cháo", word: "潮水" }, { char: "堤", pinyin: "dī", word: "堤岸" },
      { char: "阔", pinyin: "kuò", word: "宽阔" }, { char: "盼", pinyin: "pàn", word: "盼望" },
      { char: "滚", pinyin: "gǔn", word: "滚动" }, { char: "顿", pinyin: "dùn", word: "顿时" }
    ],
    2: [
      { char: "暮", pinyin: "mù", word: "暮色" }, { char: "吟", pinyin: "yín", word: "吟诵" },
      { char: "瑟", pinyin: "sè", word: "瑟瑟" }, { char: "残", pinyin: "cán", word: "残阳" },
      { char: "铺", pinyin: "pū", word: "铺开" }, { char: "犹", pinyin: "yóu", word: "犹如" }
    ]
  },
  5: {
    1: [
      { char: "白", pinyin: "bái", word: "白云" }, { char: "鹭", pinyin: "lù", word: "白鹭" },
      { char: "嫌", pinyin: "xián", word: "嫌弃" }, { char: "镜", pinyin: "jìng", word: "镜子" },
      { char: "匣", pinyin: "xiá", word: "木匣" }, { char: "沐", pinyin: "mù", word: "沐浴" }
    ],
    2: [
      { char: "搭", pinyin: "dā", word: "搭桥" }, { char: "亲", pinyin: "qīn", word: "亲人" },
      { char: "仇", pinyin: "chóu", word: "仇人" }, { char: "叮", pinyin: "dīng", word: "叮咛" },
      { char: "嘱", pinyin: "zhǔ", word: "嘱咐" }, { char: "悔", pinyin: "huǐ", word: "后悔" }
    ]
  },
  6: {
    1: [
      { char: "德", pinyin: "dé", word: "品德" }, { char: "鹊", pinyin: "què", word: "喜鹊" },
      { char: "蝉", pinyin: "chán", word: "蝉鸣" }, { char: "稻", pinyin: "dào", word: "稻谷" },
      { char: "蛙", pinyin: "wā", word: "青蛙" }, { char: "荷", pinyin: "hé", word: "荷花" }
    ],
    2: [
      { char: "毯", pinyin: "tǎn", word: "地毯" }, { char: "陈", pinyin: "chén", word: "陈列" },
      { char: "裳", pinyin: "shang", word: "衣裳" }, { char: "虹", pinyin: "hóng", word: "彩虹" },
      { char: "蹄", pinyin: "tí", word: "马蹄" }, { char: "腐", pinyin: "fǔ", word: "豆腐" }
    ]
  }
};

export const CHINESE_POEMS: Record<number, { 1: ChinesePoem; 2: ChinesePoem }> = {
  1: {
    1: { title: "咏鹅", author: "骆宾王", lines: ["鹅，鹅，鹅", "曲项向天歌", "白毛浮绿水", "红掌拨清波"] },
    2: { title: "静夜思", author: "李白", lines: ["床前明月光", "疑是地上霜", "举头望明月", "低头思故乡"] }
  },
  2: {
    1: { title: "咏柳", author: "贺知章", lines: ["碧玉妆成一树高", "万条垂下绿丝绦", "不知细叶谁裁出", "二月春风似剪刀"] },
    2: { title: "晓出净慈寺送林子方", author: "杨万里", lines: ["毕竟西湖六月中", "风光不与四时同", "接天莲叶无穷碧", "映日荷花别样红"] }
  },
  3: {
    1: { title: "山行", author: "杜牧", lines: ["远上寒山石径斜", "白云生处有人家", "停车坐爱枫林晚", "霜叶红于二月花"] },
    2: { title: "赠刘景文", author: "苏轼", lines: ["荷尽已无擎雨盖", "菊残犹有傲霜枝", "一年好景君须记", "最是橙黄橘绿时"] }
  },
  4: {
    1: { title: "题西林壁", author: "苏轼", lines: ["横看成岭侧成峰", "远近高低各不同", "不识庐山真面目", "只缘身在此山中"] },
    2: { title: "暮江吟", author: "白居易", lines: ["一道残阳铺水中", "半江瑟瑟半江红", "可怜九月初三夜", "露似真珠月似弓"] }
  },
  5: {
    1: { title: "示儿", author: "陆游", lines: ["死去元知万事空", "但悲不见九州同", "王师北定中原日", "家祭无忘告乃翁"] },
    2: { title: "题临安邸", author: "林升", lines: ["山外青山楼外楼", "西湖歌舞几时休", "暖风熏得游人醉", "直把杭州作汴州"] }
  },
  6: {
    1: { title: "宿建德江", author: "孟浩然", lines: ["移舟泊烟渚", "日暮客愁新", "野旷天低树", "江清月近人"] },
    2: { title: "六月二十七日望湖楼醉书", author: "苏轼", lines: ["黑云翻墨未遮山", "白雨跳珠乱入船", "卷地风来忽吹散", "望湖楼下水如天"] }
  }
};
