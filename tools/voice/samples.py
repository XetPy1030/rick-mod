"""Образцы голоса Рика для решения по вопросу 17 (docs/open-questions.md).

    OPENROUTER_API_KEY=… python3 tools/voice/samples.py [--models m1,m2] [--voices v1,v2]

Пишет в tools/voice/out/: по файлу на реплику и сборку всех реплик одним файлом на голос,
плюс report.md — совпадение с текстом, длительность, задержка до первого звука, цена.
"""
import argparse
import os
import sys

import tts

MANNER = ("Рик Санчез: хриплый пьяный старик-гений, говорит быстро, раздражённо и с презрением, "
          "обрывает фразы, рыгает посреди слов")

LINES = [
    "Ты опять здесь? Я думал, тебя съели. Надеялся, если честно.",
    "Это не ошибка, это *рыг* эксперимент. Ошибка — это ты.",
    "Морти, блядь. Скелет. В лесу. Без ничего в руках. Ты хоть на скелета-то посмотрел?",
    "Жопосранчик, копай на Y=-59: там алмазы встречаются чаще. И не прыгай в лаву — это не ускоряет добычу.",
    "Вот тебе задание, лаборант: шестнадцать светящихся ягод. И не спрашивай зачем. *рыг* Наука не отвечает на вопросы.",
    "Уаббалаббадабдаб!",
    "Ладно… ты молодец. Не привыкай. Это была минутная слабость, я был пьян. Я всегда пьян.",
]

PAUSE = b"\x00\x00" * int(tts.RATE * 0.8)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="openai/gpt-audio-mini")
    ap.add_argument("--voices", default="ash,ballad,echo,verse,cedar")
    args = ap.parse_args()
    key = tts.key_from_env()
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
    os.makedirs(out, exist_ok=True)
    report = ["| Модель | Голос | Реплика | Совпадение | Звук, с | Первый звук, с | Всего, с | Цена | Расшифровка |",
              "|---|---|---|---|---|---|---|---|---|"]
    total = 0.0
    for model in args.models.split(","):
        short = model.split("/")[-1]
        for voice in args.voices.split(","):
            joined = bytearray()
            for i, text in enumerate(LINES, 1):
                try:
                    s = tts.speak(key, model, voice, text, MANNER)
                except Exception as e:  # noqa: BLE001 — образцы, не прод
                    print(f"{short} {voice} #{i}: {e}", file=sys.stderr)
                    report.append(f"| {short} | {voice} | {i} | ошибка | | | | | {e} |")
                    continue
                total += s.cost
                tts.save_wav(os.path.join(out, f"{short}_{voice}_{i}.wav"), s.pcm)
                joined += s.pcm + PAUSE
                fid = tts.fidelity(text, s.transcript)
                report.append(f"| {short} | {voice} | {i} | {fid:.2f} | {s.seconds:.1f} | {s.first_s:.1f} | {s.total_s:.1f} "
                              f"| ${s.cost:.4f} | {s.transcript.replace('|', '/')} |")
                print(f"{short} {voice} #{i}: {fid:.2f}, {s.seconds:.1f} с, первый звук {s.first_s:.1f} с, ${s.cost:.4f}")
            if joined:
                tts.save_wav(os.path.join(out, f"rick_{short}_{voice}.wav"), bytes(joined))
    report.append(f"\nВсего: ${total:.4f}")
    with open(os.path.join(out, "report.md"), "w") as f:
        f.write("\n".join(report) + "\n")
    print(f"всего ${total:.4f}, отчёт: {out}/report.md")


if __name__ == "__main__":
    main()
