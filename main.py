"""Top-K 输入法控制台版入口。

  python3 main.py --provider api  --context "朋友问你：今晚一起吃饭吗"
  python3 main.py --provider mock --context "离线演示"
"""
from topk.cli import main

if __name__ == "__main__":
    main()
