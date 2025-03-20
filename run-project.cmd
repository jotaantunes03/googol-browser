@echo off
setlocal

REM Caminho do diretório do projeto no Windows
set "PROJECT_DIR=C:\Users\João Fonseca Antunes\OneDrive\Ambiente de Trabalho\Mestrado 1 ano\2 semestre\SD\Project"
set "TARGET_DIR= /mnt/c/Users/João\ Fonseca\ Antunes/OneDrive/Ambiente\ de\ Trabalho/Mestrado\ 1\ ano/2\ semestre/SD/Project/target"

REM 1. Executar o build.sh no WSL
echo Running build.sh...
wsl bash -c cd "/mnt/c/Users/João Fonseca Antunes/OneDrive/Ambiente de Trabalho/Mestrado 1 ano/2 semestre/SD/Project" && bash build.sh

REM 2. Iniciar os processos em novas abas do Windows Terminal
echo Starting processes...

wt.exe new-tab -- PowerShell -NoExit -Command "Get-Date"

echo All processes started in new terminal tabs!

