#!/usr/bin/env python3
"""Fine-tune SMaLL-100 specifically for Turkish movie dialogue -> Arabic subtitles."""
from __future__ import annotations

import argparse
import inspect
import json
import math
from pathlib import Path

import numpy as np
import torch
from datasets import load_dataset
from sacrebleu.metrics import BLEU, CHRF
from transformers import (
    AutoModelForSeq2SeqLM,
    AutoTokenizer,
    DataCollatorForSeq2Seq,
    Seq2SeqTrainer,
    Seq2SeqTrainingArguments,
    set_seed,
)

BASE_MODEL = "alirezamsh/small100"


def build_tokenizer(model_name: str):
    tokenizer = AutoTokenizer.from_pretrained(model_name, trust_remote_code=True)
    # SMaLL-100 selects the target language in the source sequence.
    if hasattr(tokenizer, "tgt_lang"):
        tokenizer.tgt_lang = "ar"
    return tokenizer


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", type=Path, default=Path("training/data"))
    parser.add_argument("--output-dir", type=Path, default=Path("training/output/manzl-movie-tr-ar-v1"))
    parser.add_argument("--model", default=BASE_MODEL)
    parser.add_argument("--epochs", type=float, default=3.0)
    parser.add_argument("--learning-rate", type=float, default=2e-5)
    parser.add_argument("--train-batch", type=int, default=8)
    parser.add_argument("--eval-batch", type=int, default=16)
    parser.add_argument("--grad-accum", type=int, default=4)
    parser.add_argument("--max-source-length", type=int, default=192)
    parser.add_argument("--max-target-length", type=int, default=192)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--max-train-samples", type=int)
    args = parser.parse_args()
    set_seed(args.seed)

    files = {
        "train": str(args.data_dir / "train.jsonl"),
        "validation": str(args.data_dir / "dev.jsonl"),
        "test": str(args.data_dir / "test.jsonl"),
    }
    for path in files.values():
        if not Path(path).is_file():
            raise SystemExit(f"Missing prepared corpus file: {path}")

    dataset = load_dataset("json", data_files=files)
    if args.max_train_samples:
        count = min(args.max_train_samples, len(dataset["train"]))
        dataset["train"] = dataset["train"].select(range(count))

    tokenizer = build_tokenizer(args.model)
    model = AutoModelForSeq2SeqLM.from_pretrained(args.model)
    model.config.use_cache = False

    def preprocess(batch):
        model_inputs = tokenizer(
            batch["src"],
            max_length=args.max_source_length,
            truncation=True,
            text_target=batch["tgt"],
        )
        # Some tokenizer versions do not apply target max length through text_target.
        if "labels" in model_inputs:
            model_inputs["labels"] = [labels[: args.max_target_length] for labels in model_inputs["labels"]]
        return model_inputs

    remove_columns = dataset["train"].column_names
    tokenized = dataset.map(preprocess, batched=True, remove_columns=remove_columns, desc="Tokenizing movie subtitles")

    bleu = BLEU(tokenize="intl")
    chrf = CHRF(word_order=2)

    def compute_metrics(eval_pred):
        predictions, labels = eval_pred
        if isinstance(predictions, tuple):
            predictions = predictions[0]
        predictions = np.where(predictions != -100, predictions, tokenizer.pad_token_id)
        labels = np.where(labels != -100, labels, tokenizer.pad_token_id)
        decoded_preds = tokenizer.batch_decode(predictions, skip_special_tokens=True)
        decoded_labels = tokenizer.batch_decode(labels, skip_special_tokens=True)
        decoded_preds = [text.strip() for text in decoded_preds]
        decoded_labels = [text.strip() for text in decoded_labels]
        return {
            "bleu": bleu.corpus_score(decoded_preds, [decoded_labels]).score,
            "chrf2": chrf.corpus_score(decoded_preds, [decoded_labels]).score,
        }

    use_cuda = torch.cuda.is_available()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    training_kwargs = dict(
        output_dir=str(args.output_dir),
        learning_rate=args.learning_rate,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.train_batch,
        per_device_eval_batch_size=args.eval_batch,
        gradient_accumulation_steps=args.grad_accum,
        weight_decay=0.01,
        warmup_ratio=0.03,
        logging_steps=50,
        save_strategy="steps",
        save_steps=1000,
        save_total_limit=2,
        eval_strategy="steps",
        eval_steps=1000,
        predict_with_generate=True,
        generation_num_beams=5,
        generation_max_length=args.max_target_length,
        load_best_model_at_end=True,
        metric_for_best_model="chrf2",
        greater_is_better=True,
        fp16=use_cuda,
        report_to="none",
        seed=args.seed,
        dataloader_num_workers=2,
    )
    # transformers 4.x used evaluation_strategy; newer 4.x accepts eval_strategy.
    sig = inspect.signature(Seq2SeqTrainingArguments.__init__)
    if "eval_strategy" not in sig.parameters:
        training_kwargs["evaluation_strategy"] = training_kwargs.pop("eval_strategy")

    train_args = Seq2SeqTrainingArguments(**training_kwargs)
    collator = DataCollatorForSeq2Seq(tokenizer=tokenizer, model=model, pad_to_multiple_of=8 if use_cuda else None)
    trainer = Seq2SeqTrainer(
        model=model,
        args=train_args,
        train_dataset=tokenized["train"],
        eval_dataset=tokenized["validation"],
        tokenizer=tokenizer,
        data_collator=collator,
        compute_metrics=compute_metrics,
    )

    trainer.train()
    trainer.save_model(str(args.output_dir))
    tokenizer.save_pretrained(str(args.output_dir))
    test_metrics = trainer.evaluate(tokenized["test"], metric_key_prefix="test")
    (args.output_dir / "test_metrics.json").write_text(
        json.dumps(test_metrics, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(test_metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
